package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadEntity
import ceui.lisa.utils.Params
import ceui.loxia.Novel
import ceui.loxia.WebNovel
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

    suspend fun export(
        context: Context,
        format: ExportFormat,
        novel: Novel?,
        webNovel: WebNovel,
        tokens: List<ContentToken>,
    ): ExportResult = withContext(Dispatchers.IO) {
        val destination: RelativePath = if (novel != null) {
            DownloadItems.novelDestinationFromLoxia(
                novel,
                extOverride = format.extension,
                // 从 payload 的前后篇导航推算系列序号，让单篇下载 / 导出和
                // 系列批量下载渲染出同一个文件名（issue #964）。
                seriesOrder = DownloadItems.seriesOrderOf(webNovel),
            )
        } else {
            // No loxia Novel — only the web payload. Best-effort meta;
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
