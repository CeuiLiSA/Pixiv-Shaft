package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import android.net.Uri
import ceui.loxia.Novel
import ceui.loxia.WebNovel
import ceui.pixiv.download.model.RelativePath
import ceui.pixiv.ui.novel.reader.model.ContentToken

/**
 * Shared contract for export formats. Each exporter turns a parsed novel (+
 * tokens from the reader's own ContentParser) into a file dropped into the
 * Novel bucket via MediaStore — directory layout governed by the user's
 * active naming preset, never hardcoded.
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
        destination: RelativePath,
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
    /**
     * [displayPath] 是模板渲染出的完整相对路径（目录 + 文件名），给成功 toast
     * 展示用——路径由用户模板决定，不能在文案里写死目录前缀。
     */
    data class Success(val uri: Uri, val displayPath: String, val format: ExportFormat) : ExportResult()
    data class Failure(val message: String, val cause: Throwable? = null) : ExportResult()
}
