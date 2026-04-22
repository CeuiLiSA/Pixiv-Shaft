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
    val displayNameResId: Int,
    val extension: String,
    val mimeType: String,
) {
    Txt(ceui.lisa.R.string.format_txt, "txt", "text/plain"),
    Markdown(ceui.lisa.R.string.format_markdown, "md", "text/markdown"),
    Epub(ceui.lisa.R.string.format_epub, "epub", "application/epub+zip"),
    Pdf(ceui.lisa.R.string.format_pdf, "pdf", "application/pdf"),
}

sealed class ExportResult {
    data class Success(val uri: Uri, val fileName: String, val format: ExportFormat) : ExportResult()
    data class Failure(val message: String, val cause: Throwable? = null) : ExportResult()
}
