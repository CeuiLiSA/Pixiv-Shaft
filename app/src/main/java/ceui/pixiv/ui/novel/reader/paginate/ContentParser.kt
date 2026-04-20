package ceui.pixiv.ui.novel.reader.paginate

import ceui.loxia.WebNovel
import ceui.pixiv.ui.novel.reader.model.ContentToken

/**
 * Tokenizes [WebNovel.text] into a stream of [ContentToken] entries.
 *
 * Pixiv's novel markup is line-oriented — every special tag ([newpage], [chapter:],
 * [pixivimage:], [uploadedimage:]) occupies its own line. Mixed-content lines
 * therefore aren't a real-world concern; if one appears we still match the
 * *first* tag we find on the line to stay compatible with the legacy parser.
 */
object ContentParser {

    private val uploadedImageRegex = Regex("""\[uploadedimage:(\d+)]""")
    private val pixivImageRegex = Regex("""\[pixivimage:(\d+)(?:-(\d+))?]""")
    private val chapterRegex = Regex("""\[chapter:(.+?)]""")
    private const val NEWPAGE_TAG = "[newpage]"

    fun tokenize(webNovel: WebNovel): List<ContentToken> = tokenize(webNovel.text.orEmpty())

    fun tokenize(text: String): List<ContentToken> {
        if (text.isEmpty()) return emptyList()
        val raw = ArrayList<ContentToken>(128)
        var cursor = 0
        for (line in text.split('\n')) {
            val lineStart = cursor
            val lineEnd = lineStart + line.length
            cursor = lineEnd + 1 // newline that split() consumed

            // Strip trailing CR: Pixiv novel responses use `\r\n` endings, and
            // splitting on `\n` alone leaves the CR inside each line. The CR
            // survives into Paragraph.text → StaticLayout treats U+000D as a
            // line-break control, forcing a hard break mid-paragraph and
            // stretching the visual gap there. `lineStart` / `lineEnd` still
            // point at the original source positions so char-based anchors
            // (bookmarks, selection) stay stable.
            val cleanLine = line.trimEnd('\r')
            val trimmed = cleanLine.trim()
            when {
                trimmed == NEWPAGE_TAG -> {
                    raw += ContentToken.PageBreak(lineStart, lineEnd)
                }
                chapterRegex.containsMatchIn(trimmed) -> {
                    val title = chapterRegex.find(trimmed)?.groupValues?.getOrNull(1).orEmpty().trim()
                    raw += ContentToken.Chapter(lineStart, lineEnd, title)
                }
                uploadedImageRegex.containsMatchIn(trimmed) -> {
                    val m = uploadedImageRegex.find(trimmed)!!
                    val id = m.groupValues[1].toLongOrNull() ?: 0L
                    raw += ContentToken.UploadedImage(lineStart, lineEnd, id)
                }
                pixivImageRegex.containsMatchIn(trimmed) -> {
                    val m = pixivImageRegex.find(trimmed)!!
                    val id = m.groupValues[1].toLongOrNull() ?: 0L
                    val page = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                    raw += ContentToken.PixivImage(lineStart, lineEnd, id, page)
                }
                cleanLine.isEmpty() -> {
                    raw += ContentToken.BlankLine(lineStart, lineEnd)
                }
                else -> {
                    raw += ContentToken.Paragraph(lineStart, lineEnd, cleanLine)
                }
            }
        }
        return coalesceParagraphBreaks(raw)
    }

    /**
     * `\n\n` in a novel source is the conventional paragraph separator, not
     * "paragraph + extra blank line". The paginator already puts
     * [ceui.pixiv.ui.novel.reader.paginate.TypeStyle.paragraphSpacingPx]
     * between consecutive paragraphs; if we also emit a [BlankLine] for the
     * embedded empty line, both gaps compound and every paragraph break
     * reads as two full lines of whitespace.
     *
     * Coalesce: drop the FIRST [BlankLine] that directly follows a
     * [Paragraph]. Any additional BlankLines (`\n\n\n+`) stay as real extra
     * breathing space, and BlankLines after chapter headings / images /
     * at the start of the doc pass through unchanged.
     */
    private fun coalesceParagraphBreaks(raw: List<ContentToken>): List<ContentToken> {
        val out = ArrayList<ContentToken>(raw.size)
        var swallowedParagraphBreak = false
        for (tok in raw) {
            when (tok) {
                is ContentToken.BlankLine -> {
                    val prev = out.lastOrNull()
                    if (prev is ContentToken.Paragraph && !swallowedParagraphBreak) {
                        swallowedParagraphBreak = true
                        continue
                    }
                    out += tok
                }
                else -> {
                    swallowedParagraphBreak = false
                    out += tok
                }
            }
        }
        return out
    }

    /** Collect the chapter outline for the drawer. Includes synthetic "前言" if content
     *  appears before any explicit [chapter:] tag. Page-break-only sections are ignored. */
    fun buildChapterOutline(tokens: List<ContentToken>): List<ChapterOutlineEntry> {
        val outline = mutableListOf<ChapterOutlineEntry>()
        var hasContentBeforeChapter = false
        for (token in tokens) {
            when (token) {
                is ContentToken.Chapter -> {
                    outline += ChapterOutlineEntry(
                        title = token.title,
                        sourceStart = token.sourceStart,
                    )
                }
                is ContentToken.Paragraph,
                is ContentToken.PixivImage,
                is ContentToken.UploadedImage,
                -> {
                    if (outline.isEmpty() && !hasContentBeforeChapter) {
                        outline += ChapterOutlineEntry(title = "前言", sourceStart = token.sourceStart)
                        hasContentBeforeChapter = true
                    }
                }
                else -> Unit
            }
        }
        return outline
    }
}

data class ChapterOutlineEntry(
    val title: String,
    val sourceStart: Int,
)
