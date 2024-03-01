package ceui.lisa.models

data class WebNovel(
    val aiType: Int? = null,
    val caption: String? = null,
    val coverUrl: String? = null,
    val glossaryItems: List<Any?>? = null,
    val id: String? = null,
    val text: String? = null,
    val illusts: List<Any?>? = null,
    val images: List<Any?>? = null,
    val isOriginal: Boolean? = null,
    val marker: Any? = null,
    val replaceableItemIds: List<Any?>? = null,
    val seriesId: String? = null,
    val seriesIsWatched: Boolean? = null,
    val seriesNavigation: SeriesNavigation? = null,
    val seriesTitle: String? = null,
    val tags: List<String?>? = null,
    val title: String? = null,
    val userId: String? = null
)

data class SeriesNavigation(
    val nextNovel: NovelBean? = null,
    val prevNovel: NovelBean? = null
)