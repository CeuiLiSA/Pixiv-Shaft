package ceui.pixiv.ui.novel.reader.feature

import ceui.pixiv.ui.novel.reader.model.Page
import ceui.pixiv.ui.novel.reader.model.SearchHit

/**
 * Pure search over the raw source text. Hits are reported with absolute source
 * offsets so the caller can locate them on the current pagination.
 *
 * Supports literal (default) and regex (advanced) modes; case sensitivity is a
 * separate flag. Literal mode escapes [query] so metacharacters behave as text.
 */
object SearchEngine {

    private const val SNIPPET_RADIUS = 18

    fun search(
        sourceText: String,
        query: String,
        regex: Boolean,
        caseSensitive: Boolean,
    ): List<SearchHit> {
        if (query.isEmpty() || sourceText.isEmpty()) return emptyList()
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val pattern = runCatching {
            if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        }.getOrNull() ?: return emptyList()

        val hits = ArrayList<SearchHit>()
        for (match in pattern.findAll(sourceText)) {
            val start = match.range.first
            val end = match.range.last + 1
            if (end == start) continue // skip zero-width matches
            hits += SearchHit(
                absoluteStart = start,
                absoluteEnd = end,
                pageIndex = -1,
                snippet = extractSnippet(sourceText, start, end),
            )
            if (hits.size >= MAX_HITS) break
        }
        return hits
    }

    /** Annotate [hits] with page indices based on [pages]. Returns a new list
     *  so the input stays immutable. */
    fun annotatePageIndices(hits: List<SearchHit>, pages: List<Page>): List<SearchHit> {
        if (hits.isEmpty() || pages.isEmpty()) return hits
        val result = ArrayList<SearchHit>(hits.size)
        var pageCursor = 0
        for (hit in hits) {
            while (pageCursor < pages.size && pages[pageCursor].charEnd < hit.absoluteStart) {
                pageCursor++
            }
            val page = pages.getOrNull(pageCursor)
            result += hit.copy(pageIndex = page?.index ?: -1)
        }
        return result
    }

    private fun extractSnippet(source: String, start: Int, endExclusive: Int): String {
        val s = (start - SNIPPET_RADIUS).coerceAtLeast(0)
        val e = (endExclusive + SNIPPET_RADIUS).coerceAtMost(source.length)
        return source.substring(s, e)
            .replace('\n', ' ')
            .trim()
    }

    private const val MAX_HITS = 2000
}
