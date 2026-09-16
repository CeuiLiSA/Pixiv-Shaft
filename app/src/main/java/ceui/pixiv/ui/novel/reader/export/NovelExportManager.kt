package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadEntity
import ceui.lisa.utils.Params
import ceui.loxia.Novel
import ceui.pixiv.api.model.WebNovel
import ceui.pixiv.cache.SeriesCache
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.model.RelativePath
import ceui.pixiv.ui.novel.reader.model.ContentToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Entry point for export actions. Picks an exporter by [ExportFormat],
 * resolves the destination path through the user's active naming preset
 * (via [DownloadItems.novelDestinationFromLoxia]), and shuffles the call
 * off the main thread. The directory + filename layout is the same one the
 * queued downloader uses — there must not be a separate "reader export"
 * naming scheme.
 */
object NovelExportManager {

    private val exporters: Map<ExportFormat, NovelExporter> = mapOf(
        ExportFormat.Txt to TxtExporter(),
        ExportFormat.Markdown to MarkdownExporter(),
        ExportFormat.Epub to EpubExporter(),
        ExportFormat.Pdf to PdfExporter(),
    )

    /**
     * 解析「默认小说下载格式」设置。
     *
     * @return 非空 = 直接使用该格式；null = 设置为「每次询问」，调用方应弹格式选择。
     */
    fun resolveConfiguredFormat(): ExportFormat? {
        val name = Shaft.sSettings.defaultNovelExportFormat
        return if (name.isNullOrBlank()) null else ExportFormat.entries.firstOrNull { it.name == name }
    }

    /**
     * 默认 TXT 快路径下，正文含插图且用户开启「有插图时存 EPUB」时改用 EPUB。
     * 手动在“每次询问”里选择 TXT 不应触发。
     */
    fun shouldAutoEpubForDefaultTxt(format: ExportFormat, tokens: List<ContentToken>): Boolean =
        format == ExportFormat.Txt &&
            resolveConfiguredFormat() == ExportFormat.Txt &&
            Shaft.sSettings.isDefaultNovelExportEpubOnImages &&
            tokens.any { it is ContentToken.PixivImage || it is ContentToken.UploadedImage }

    suspend fun export(
        context: Context,
        format: ExportFormat,
        novel: Novel?,
        webNovel: WebNovel,
        tokens: List<ContentToken>,
        seriesOrder: Int? = null,
        seriesTotal: Int? = null,
    ): ExportResult = withContext(Dispatchers.IO) {
        val destination: RelativePath = if (novel != null) {
            DownloadItems.novelDestinationFromLoxia(
                novel,
                extOverride = format.extension,
                // 序号取「系列可见列表位置」（SeriesCache，reader 场景通常已缓存），
                // 让单篇下载 / 导出和系列批量下载渲染出同一个文件名（issue #964）。
                // 批量/系列下载调用方可直接传 seriesOrder/seriesTotal 覆盖缓存查询。
                seriesOrder = seriesOrder ?: seriesOrderOf(novel),
                seriesTotal = seriesTotal,
            )
        } else {
            // No Novel — only the web payload. Best-effort meta;
            // templates that lean on author/created get blanks, but the
            // path still respects the user's preset.
            DownloadItems.novelDestinationFromWeb(
                webNovelId = webNovel.id,
                webNovelTitle = webNovel.title,
                extOverride = format.extension,
            )
        }
        val exporter = exporters[format]
            ?: return@withContext ExportResult.Failure(context.getString(ceui.lisa.R.string.msg_unknown_format, context.getString(format.displayNameResId)))
        // Kotlin stdlib runCatching 会把 CancellationException 一起捕到,变成
        // ExportResult.Failure 弹「Job was cancelled」给用户(单本导出场景同样
        // 受影响)。改成手写 try/catch,显式重抛 CE。
        val result = try {
            exporter.export(context, novel, webNovel, tokens, destination)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // The message reaches the user via the toast; log the full stack so
            // environment-specific write failures (OEM MediaStore quirks, SAF
            // permission loss, …) are diagnosable from a bug report instead of
            // hiding behind a one-line message.
            Timber.e(t, "novel export failed: format=$format id=${novel?.id} dest=${destination.joinTo()}")
            ExportResult.Failure(t.message ?: "导出失败", t)
        }
        if (result is ExportResult.Success) {
            recordDownload(novel, result)
        }
        result
    }

    /**
     * 本篇在系列中的 1-based 序号；不在系列里 / 拉不到系列列表时返回 null
     * （`{series_order}` 渲染为空）。序号查询失败绝不能拖垮导出本身——
     * 吞掉网络异常但放行取消。
     */
    private suspend fun seriesOrderOf(novel: Novel): Int? {
        val seriesId = novel.series?.id?.takeIf { it > 0 } ?: return null
        return try {
            SeriesCache.novelPositionInSeries(seriesId, novel.id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "series order lookup failed for novel ${novel.id}, exporting without order")
            null
        }
    }

    // 同 FragmentNovelHolder 旧版「保存」逻辑：以 NOVEL_KEY+id 作为主键，让
    // DownloadedAdapter 通过 fileName.contains(NOVEL_KEY) 把它识别为小说条目。
    // 多次/不同格式导出会 REPLACE 同一行，下载历史里每本小说只占一格。
    private fun recordDownload(novel: Novel?, success: ExportResult.Success) {
        val id = novel?.id ?: return
        runCatching {
            val entity = DownloadEntity().apply {
                fileName = Params.NOVEL_KEY + id
                downloadTime = System.currentTimeMillis()
                filePath = success.uri.toString()
                illustGson = Shaft.sGson.toJson(novel)
            }
            AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insertDownload(entity)
        }.onFailure { Timber.e(it, "recordDownload failed for novel $id") }
    }
}
