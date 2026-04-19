package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import android.net.Uri
import ceui.loxia.Novel
import ceui.loxia.WebNovel
import ceui.pixiv.ui.novel.reader.model.ContentToken

/**
 * Shared contract for export formats. Each exporter turns a parsed novel (+
 * tokens from the reader's own ContentParser) into a file dropped into the
 * public Downloads/ShaftNovels folder via MediaStore.
 *
 * Exporters run on [kotlinx.coroutines.Dispatchers.IO]; they own any network
 * I/O they trigger (e.g. Glide sync bitmap loads for embedded images) and
 * must bail out gracefully on failure — the caller surfaces the [ExportResult].
 */
interface NovelExporter {
    val format: ExportFormat

    suspend fun export(
        context: Context,
        novel: Novel?,
        webNovel: WebNovel,
        tokens: List<ContentToken>,
        fileName: String,
    ): ExportResult
}

enum class ExportFormat(
    val displayName: String,
    val extension: String,
    val mimeType: String,
) {
    Txt("纯文本 TXT", "txt", "text/plain"),
    Markdown("Markdown", "md", "text/markdown"),
    Epub("EPUB 电子书", "epub", "application/epub+zip"),
    Pdf("PDF", "pdf", "application/pdf"),
}

sealed class ExportResult {
    data class Success(val uri: Uri, val fileName: String, val format: ExportFormat) : ExportResult()
    data class Failure(val message: String, val cause: Throwable? = null) : ExportResult()
}
