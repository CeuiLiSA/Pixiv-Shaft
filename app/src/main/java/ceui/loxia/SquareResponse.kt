package ceui.loxia

import java.io.Serializable

interface WebApiResponse<T> : Serializable {
    val error: Boolean?
    val message: String?
    val body: T?
}

data class CircleResponse(
    override val error: Boolean? = null,
    override val message: String? = null,
    override val body: Circle? = null
) : WebApiResponse<Circle>

data class Circle(
    val illusts: List<WebIllust>? = null,
    val total: Int = 0,
    val lastPage: Int = 0,
    val meta: CircleMeta? = null
) : Serializable

data class CirclePopularIllusts(
    val illusts: List<WebIllust>? = null,
    val recent_illusts: List<WebIllust>? = null,
) : Serializable

data class Pixpedia(
    val tag: String? = null,
    val abstract: String? = null,
    val illust: WebIllust? = null,
    val parent_tag: String? = null,
    val siblings_tags: List<String>? = null,
    val children_tags: List<String>? = null,
    val breadcrumbs: List<String>? = null,
) : Serializable {

    val pixpediaTags: List<Tag> get() {
        val result = mutableListOf<Tag>()
        siblings_tags?.forEach {
            result.add(Tag(name = it))
        }
        children_tags?.forEach {
            result.add(Tag(name = it))
        }
        breadcrumbs?.forEach {
            result.add(Tag(name = it))
        }
        return result
    }
}

data class CircleMeta(
    val tag: String? = null,
    val translatedTag: String? = null,
    val pixpedia: Pixpedia? = null,
    val meta: CircleChildMeta? = null,
    val words: List<String>? = null,
    val relatedTags: List<WebTag>? = null,
) : Serializable

data class CircleChildMeta(
    val title: String? = null,
    val canonical: String? = null,
    val description: String? = null,
    val description_header: String? = null,
) : Serializable

data class SquareResponse(
    override val error: Boolean? = null,
    override val message: String? = null,
    override val body: Square? = null
) : WebApiResponse<Square>

data class Square(
    val tagTranslation: Map<String, TranslatedTags>? = null,
    val page: Page? = null,
    val total: Int? = null,
    val thumbnails: Thumbnails? = null,
) : Serializable

data class TranslatedTags(
    val en: String? = null,
    val ko: String? = null,
    val zh: String? = null,
    val zh_tw: String? = null,
    val romaji: String? = null,
) : Serializable

data class Page(
    val recommendByTag: List<WebTag>? = null,
    val trendingTags: List<WebTag>? = null,
    val tags: List<WebTag>? = null,
    val follow: List<Long>? = null,
    val ranking: RankingHolder? = null,
) : Serializable

data class Thumbnails(
    val illust: List<WebIllust>? = null
) : Serializable

data class RankingHolder(
    val date: String? = null,
    val items: List<RankingItem>? = null
) : Serializable

data class RankingItem(
    val rank: Int = 0,
    val id: Long = 0L
) : Serializable
