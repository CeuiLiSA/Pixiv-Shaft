package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import ceui.loxia.Novel
import ceui.loxia.WebNovel
import ceui.pixiv.ui.novel.reader.model.ContentToken
import ceui.pixiv.ui.novel.reader.paginate.ImageResolver

/**
 * Markdown export. Preserves structure: front-matter-style metadata header,
 * chapters as `##` headings, paragraphs as bodies, images as inline
 * `![alt](url)` pointing at the CDN so the output stays portable to anywhere
 * that renders Markdown.
 */
class MarkdownExporter : NovelExporter {
    override val format: ExportFormat = ExportFormat.Markdown

    override suspend fun export(
        context: Context,
        novel: Novel?,
        webNovel: WebNovel,
        tokens: List<ContentToken>,
        fileName: String,
    ): ExportResult {
        val resolver = ImageResolver.of(webNovel)
        val title = novel?.title ?: webNovel.title.orEmpty()
        val author = novel?.user?.name.orEmpty()
        val novelId = novel?.id ?: webNovel.id.orEmpty()
        val url = "https://www.pixiv.net/novel/show.php?id=$novelId"
        val caption = ExportUtils.brToNewline(webNovel.caption)
        val tags = novel?.tags?.mapNotNull { it.name }?.joinToString(", ").orEmpty()

        val text = buildString {
            appendLine("# ${escape(title)}")
            appendLine()
            if (author.isNotEmpty()) appendLine("**作者**: ${escape(author)}  ")
            appendLine("**来源**: [$url]($url)  ")
            if (tags.isNotEmpty()) appendLine("**标签**: ${escape(tags)}  ")
            appendLine()
            if (caption.isNotEmpty()) {
                appendLine("> ${caption.replace("\n", "  \n> ")}")
                appendLine()
            }
            appendLine("---")
            appendLine()

            for (token in tokens) {
                when (token) {
                    is ContentToken.Paragraph -> {
                        appendLine(escape(token.text))
                        appendLine()
                    }
                    is ContentToken.BlankLine -> appendLine()
                    is ContentToken.PageBreak -> {
                        appendLine()
                        appendLine("---")
                        appendLine()
                    }
                    is ContentToken.Chapter -> {
                        appendLine()
                        appendLine("## ${escape(token.title)}")
                        appendLine()
                    }
                    is ContentToken.PixivImage -> {
                        val imgUrl = resolver(token) ?: ""
                        if (imgUrl.isNotEmpty()) {
                            appendLine("![pixiv ${token.illustId}]($imgUrl)")
                        } else {
                            appendLine("*[图片 pixiv ${token.illustId} 无法解析]*")
                        }
                        appendLine()
                    }
                    is ContentToken.UploadedImage -> {
                        val imgUrl = resolver(token) ?: ""
                        if (imgUrl.isNotEmpty()) {
                            appendLine("![uploaded ${token.imageId}]($imgUrl)")
                        } else {
                            appendLine("*[图片 ${token.imageId} 无法解析]*")
                        }
                        appendLine()
                    }
                }
            }
        }
        val uri = ExportUtils.saveToDownloads(context, fileName, format.mimeType) {
            it.write(text.toByteArray(Charsets.UTF_8))
        } ?: return ExportResult.Failure("无法写入 Downloads")
        return ExportResult.Success(uri, fileName, format)
    }

    /** Minimal Markdown escaping — just the metacharacters that break inline structures. */
    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("`", "\\`")
}
