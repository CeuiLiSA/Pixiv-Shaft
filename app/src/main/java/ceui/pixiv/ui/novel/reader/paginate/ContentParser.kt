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
            // Strip leading whitespace from paragraph content: Pixiv authors
            // often type `　`/space at the start for manual CJK indent. Our
            // TypeStyle.firstLineIndentPx already handles the indent via
            // LeadingMarginSpan, so leaving the manual space in would double
            // up (2em auto + 1em manual ≈ 3em visible). Trim them here and
            // let the auto-indent own the offset.
            val paragraphText = cleanLine.trimStart(' ', '\t', '\u3000')
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
                trimmed.isEmpty() -> {
                    // Treat whitespace-only lines (including lines containing
                    // nothing but `　`) as blank — otherwise they rendered as
                    // ghost 1-char paragraphs with an auto-indent applied.
                    raw += ContentToken.BlankLine(lineStart, lineEnd)
                }
                else -> {
                    raw += ContentToken.Paragraph(lineStart, lineEnd, paragraphText)
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

    /** Collect the chapter outline for the drawer. Mixes explicit [chapter:]
     *  entries with `[newpage]`-derived "分页 N" entries so users get the same
     *  navigation granularity that Pixiv's web reader exposes. A synthetic
     *  "前言" / "分页 1" fronts the list when the first real content precedes
     *  every anchor, so the doc's opening is always jumpable. */
    fun buildChapterOutline(tokens: List<ContentToken>): List<ChapterOutlineEntry> {
        val outline = mutableListOf<ChapterOutlineEntry>()
        val hasPageBreaks = tokens.any { it is ContentToken.PageBreak }
        var firstContentAnchored = false
        // Counts pages as Pixiv does: the span before the first [newpage] is
        // page 1, and each subsequent break starts the next page. Incremented
        // on PageBreak so the emitted label is `page + 1`.
        var breaksSeen = 0
        for (token in tokens) {
            when (token) {
                is ContentToken.Chapter -> {
                    outline += ChapterOutlineEntry(
                        title = token.title,
                        sourceStart = token.sourceStart,
                    )
                    firstContentAnchored = true
                }
                is ContentToken.PageBreak -> {
                    breaksSeen += 1
                    outline += ChapterOutlineEntry(
                        title = "分页 ${breaksSeen + 1}",
                        sourceStart = token.sourceEnd,
                    )
                    firstContentAnchored = true
                }
                is ContentToken.Paragraph,
                is ContentToken.PixivImage,
                is ContentToken.UploadedImage,
                -> {
                    if (!firstContentAnchored) {
                        outline += ChapterOutlineEntry(
                            title = if (hasPageBreaks) "分页 1" else "前言",
                            sourceStart = token.sourceStart,
                        )
                        firstContentAnchored = true
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
