package ceui.pixiv.ui.novel.reader.export

import android.content.Context
import ceui.loxia.Novel
import ceui.loxia.WebNovel
import ceui.pixiv.download.header.HeaderConfigRepo
import ceui.pixiv.download.header.NovelHeaderRenderer
import ceui.pixiv.ui.novel.reader.model.ContentToken

/**
 * Plain-text dump. Strips every tag, flattens images to `[图片]`, keeps chapter
 * headings as bracketed titles for scanability. The metadata header is now
 * driven by the user's preset from "下载内容信息头设置" — the hardcoded
 * title/author/source block that used to live here was replaced.
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
            if (novel != null) {
                append(
                    NovelHeaderRenderer.render(
                        novel = novel,
                        preset = HeaderConfigRepo.activePreset(),
                        isSeriesChapter = novel.series != null,
                    )
                )
                appendLine()
            } else {
                // Fallback when we only have the web payload — keep the old
                // minimal layout so the dump is still readable.
                val title = webNovel.title.orEmpty()
                if (title.isNotEmpty()) {
                    appendLine(title)
                    appendLine()
                }
                append("来源: https://www.pixiv.net/novel/show.php?id=")
                appendLine(webNovel.id.orEmpty())
                appendLine()
                val caption = ExportUtils.brToNewline(webNovel.caption)
                if (caption.isNotEmpty()) {
                    appendLine("[简介]")
                    appendLine(caption)
                    appendLine()
                }
                appendLine("-----")
                appendLine()
            }
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
                    is ContentToken.Jump -> appendLine("[跳转→第 ${token.target} 段]")
                }
            }
        }
        val uri = ExportUtils.saveToDownloads(context, fileName, format.mimeType) {
            it.write(text.toByteArray(Charsets.UTF_8))
        } ?: return ExportResult.Failure("无法写入 Downloads")
        return ExportResult.Success(uri, fileName, format)
    }
}
