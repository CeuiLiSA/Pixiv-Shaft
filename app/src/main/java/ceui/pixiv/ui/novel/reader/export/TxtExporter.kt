package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import ceui.loxia.Novel
import ceui.loxia.WebNovel
import ceui.pixiv.ui.novel.reader.model.ContentToken

/**
 * Plain-text dump. Strips every tag, flattens images to `[图片]`, keeps chapter
 * headings as bracketed titles for scanability. No formatting, no metadata
 * bloat — just the raw narrative.
 */
class TxtExporter : NovelExporter {
    override val format: ExportFormat = ExportFormat.Txt

    override suspend fun export(
        context: Context,
        novel: Novel?,
        webNovel: WebNovel,
        tokens: List<ContentToken>,
        fileName: String,
    ): ExportResult {
        val text = buildString {
            val title = novel?.title ?: webNovel.title.orEmpty()
            if (title.isNotEmpty()) {
                appendLine(title)
                appendLine()
            }
            val author = novel?.user?.name.orEmpty()
            if (author.isNotEmpty()) {
                appendLine("作者: $author")
            }
            append("来源: https://www.pixiv.net/novel/show.php?id=")
            appendLine(novel?.id ?: webNovel.id.orEmpty())
            appendLine()
            val caption = ExportUtils.brToNewline(webNovel.caption)
            if (caption.isNotEmpty()) {
                appendLine("[简介]")
                appendLine(caption)
                appendLine()
            }
            appendLine("-----")
            appendLine()
            for (token in tokens) {
                when (token) {
                    is ContentToken.Paragraph -> {
                        appendLine(token.text)
                    }
                    is ContentToken.BlankLine -> appendLine()
                    is ContentToken.PageBreak -> {
                        appendLine()
                        appendLine("- - - - - - - - - -")
                        appendLine()
                    }
                    is ContentToken.Chapter -> {
                        appendLine()
                        appendLine("【${token.title}】")
                        appendLine()
                    }
                    is ContentToken.PixivImage -> appendLine("[图片: pixiv ${token.illustId}]")
                    is ContentToken.UploadedImage -> appendLine("[图片: uploaded ${token.imageId}]")
                }
            }
        }
        val uri = ExportUtils.saveToDownloads(context, fileName, format.mimeType) {
            it.write(text.toByteArray(Charsets.UTF_8))
        } ?: return ExportResult.Failure("无法写入 Downloads")
        return ExportResult.Success(uri, fileName, format)
    }
}
