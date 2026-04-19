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
        val tokens = ArrayList<ContentToken>(128)
        var cursor = 0
        for (line in text.split('\n')) {
            val lineStart = cursor
            val lineEnd = lineStart + line.length
            cursor = lineEnd + 1 // newline that split() consumed

            val trimmed = line.trim()
            when {
                trimmed == NEWPAGE_TAG -> {
                    tokens += ContentToken.PageBreak(lineStart, lineEnd)
                }
                chapterRegex.containsMatchIn(trimmed) -> {
                    val title = chapterRegex.find(trimmed)?.groupValues?.getOrNull(1).orEmpty().trim()
                    tokens += ContentToken.Chapter(lineStart, lineEnd, title)
                }
                uploadedImageRegex.containsMatchIn(trimmed) -> {
                    val m = uploadedImageRegex.find(trimmed)!!
                    val id = m.groupValues[1].toLongOrNull() ?: 0L
                    tokens += ContentToken.UploadedImage(lineStart, lineEnd, id)
                }
                pixivImageRegex.containsMatchIn(trimmed) -> {
                    val m = pixivImageRegex.find(trimmed)!!
                    val id = m.groupValues[1].toLongOrNull() ?: 0L
                    val page = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
                    tokens += ContentToken.PixivImage(lineStart, lineEnd, id, page)
                }
                line.isEmpty() -> {
                    tokens += ContentToken.BlankLine(lineStart, lineEnd)
                }
                else -> {
                    tokens += ContentToken.Paragraph(lineStart, lineEnd, line)
                }
            }
        }
        return tokens
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
