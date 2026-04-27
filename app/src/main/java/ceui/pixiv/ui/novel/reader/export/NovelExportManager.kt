package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadEntity
import ceui.lisa.utils.Params
import ceui.loxia.Novel
import ceui.loxia.WebNovel
import ceui.pixiv.ui.novel.reader.model.ContentToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Entry point for export actions. Picks an exporter by [ExportFormat], wraps
 * filename sanitisation, and shuffles the call off the main thread.
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
        val baseName = ExportUtils.sanitize(
            novel?.title ?: webNovel.title?.takeIf { it.isNotBlank() } ?: "novel_${novel?.id ?: webNovel.id ?: "x"}",
        )
        val fileName = "$baseName.${format.extension}"
        val exporter = exporters[format]
            ?: return@withContext ExportResult.Failure(context.getString(ceui.lisa.R.string.msg_unknown_format, context.getString(format.displayNameResId)))
        val result = runCatching {
            exporter.export(context, novel, webNovel, tokens, fileName)
        }.getOrElse { ExportResult.Failure(it.message ?: "导出失败", it) }
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
            AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao().insert(entity)
        }.onFailure { Timber.e(it, "recordDownload failed for novel $id") }
    }
}
