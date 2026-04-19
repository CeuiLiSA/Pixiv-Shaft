package ceui.pixiv.ui.novel.reader.model

data class SearchHit(
    val absoluteStart: Int,
    val absoluteEnd: Int,
    val pageIndex: Int,
    val snippet: String,
)

data class SearchState(
    val query: String,
    val hits: List<SearchHit>,
    val currentHitIndex: Int,
    val isRegex: Boolean,
    val caseSensitive: Boolean,
) {
    val currentHit: SearchHit? = hits.getOrNull(currentHitIndex)
    val hitCount: Int get() = hits.size

    companion object {
        val EMPTY = SearchState(
            query = "",
            hits = emptyList(),
            currentHitIndex = -1,
            isRegex = false,
            caseSensitive = false,
        )
    }
}
