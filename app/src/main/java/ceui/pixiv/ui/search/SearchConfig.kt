package ceui.pixiv.ui.search

data class SearchConfig(
    val keyword: String,
    val sort: String = "date_desc",
    val usersYori: String = "",
    val search_target: String = "partial_match_for_tags",
    val merge_plain_keyword_results: Boolean = true,
    val include_translated_tag_results: Boolean = true,
)