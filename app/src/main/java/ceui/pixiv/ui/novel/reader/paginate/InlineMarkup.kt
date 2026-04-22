package ceui.pixiv.ui.novel.reader.paginate

/**
 * Inline markup tags embedded within Pixiv novel paragraph text.
 * Each subclass carries the data needed for rendering (e.g. URL for links,
 * ruby text for furigana).
 *
 * New tag types: add a subclass here + a corresponding [InlineTagParser].
 */
sealed class InlineTag {
    data class Link(val url: String) : InlineTag()
    data class Ruby(val rubyText: String) : InlineTag()
}

/**
 * A span of inline markup within a paragraph's display text.
 * [start] and [end] are character offsets in the **processed** (stripped) text.
 */
data class InlineSpan(val start: Int, val end: Int, val tag: InlineTag)

/**
 * Strategy interface for parsing one kind of inline tag. Each parser
 * declares the [Regex] it handles and how to extract display text + tag data
 * from a match.
 *
 * To support a new Pixiv inline tag, implement this interface and register
 * the parser in [InlineMarkupProcessor.parsers].
 */
interface InlineTagParser {
    val pattern: Regex
    /** The text that should appear in the rendered paragraph. */
    fun displayText(match: MatchResult): String
    /** The semantic tag data (link URL, ruby text, etc.). */
    fun parseTag(match: MatchResult): InlineTag
}

// ── Built-in parsers ────────────────────────────────────────────────

/** `[[jumpuri:display text>URL]]` → clickable link.
 *  Uses negated char classes to prevent catastrophic backtracking on unclosed tags. */
object JumpUriParser : InlineTagParser {
    override val pattern = Regex("""\[\[jumpuri:([^>]+)>([^]]+)]]""")
    override fun displayText(match: MatchResult): String = match.groupValues[1]
    override fun parseTag(match: MatchResult): InlineTag = InlineTag.Link(match.groupValues[2])
}

/** `[[rb:base text>ruby text]]` → furigana / annotation */
object RubyParser : InlineTagParser {
    override val pattern = Regex("""\[\[rb:([^>]+)>([^]]+)]]""")
    override fun displayText(match: MatchResult): String = match.groupValues[1]
    override fun parseTag(match: MatchResult): InlineTag = InlineTag.Ruby(match.groupValues[2])
}

/**
 * Processes raw paragraph text through all registered [InlineTagParser]s.
 * Returns the cleaned display text and a list of [InlineSpan]s with
 * positions mapped to the cleaned text.
 *
 * Extensibility: add new parsers to [parsers] — no other code changes needed.
 */
object InlineMarkupProcessor {

    private val parsers: List<InlineTagParser> = listOf(
        JumpUriParser,
        RubyParser,
    )

    data class Result(val text: String, val spans: List<InlineSpan>)

    fun process(raw: String): Result {
        // Fast path: no `[[` means no inline tags possible — skip regex entirely.
        if (!raw.contains("[[")) return Result(raw, emptyList())

        val matches = mutableListOf<TagMatch>()
        for (parser in parsers) {
            for (m in parser.pattern.findAll(raw)) {
                matches += TagMatch(m.range, parser.displayText(m), parser.parseTag(m))
            }
        }
        if (matches.isEmpty()) return Result(raw, emptyList())
        matches.sortBy { it.range.first }

        // Build cleaned text and map spans to new positions.
        val sb = StringBuilder(raw.length)
        val spans = mutableListOf<InlineSpan>()
        var cursor = 0
        for (tm in matches) {
            if (tm.range.first < cursor) continue // overlapping match, skip
            sb.append(raw, cursor, tm.range.first)
            val spanStart = sb.length
            sb.append(tm.displayText)
            val spanEnd = sb.length
            spans += InlineSpan(spanStart, spanEnd, tm.tag)
            cursor = tm.range.last + 1
        }
        sb.append(raw, cursor, raw.length)

        return Result(sb.toString(), spans)
    }

    private class TagMatch(
        val range: IntRange,
        val displayText: String,
        val tag: InlineTag,
    )
}
