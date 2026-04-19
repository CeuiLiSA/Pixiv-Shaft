package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import ceui.loxia.Novel
import ceui.loxia.WebNovel
import ceui.pixiv.ui.novel.reader.model.ContentToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            ?: return@withContext ExportResult.Failure("未知格式: ${format.displayName}")
        runCatching {
            exporter.export(context, novel, webNovel, tokens, fileName)
        }.getOrElse { ExportResult.Failure(it.message ?: "导出失败", it) }
    }
}
