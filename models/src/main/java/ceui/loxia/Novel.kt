package ceui.loxia

import ceui.lisa.models.IllustAIType
import ceui.lisa.models.ModelObject
import ceui.lisa.models.ObjectSpec
import ceui.lisa.models.Starable
import java.io.Serializable

/** A pixiv work tag shared by illustrations and novels. */
data class Tag(
    val name: String? = null,
    val translated_name: String? = null,
    // Present in app-api novel payloads. Keeping it here makes old Novel JSON round-trip intact.
    val added_by_uploaded_user: Boolean = false,
) : Serializable {
    val tagName: String? get() = name ?: translated_name
}

data class Series(
    val id: Long = 0L,
    val title: String? = null,
) : Serializable

/**
 * App 内唯一的小说模型。
 *
 * 主字段对齐 app-api 的 Kotlin Novel；末尾的客户端/旧响应字段覆盖原 Java 模型曾经持有的
 * JSON key，保证历史记录、屏蔽记录和下载缓存可以直接读取。属性保留可变性，供少量 legacy
 * Java 列表通过 [Starable] 更新收藏态；新代码仍应优先使用 [copy] / [withBookmarked]。
 */
data class Novel(
    var caption: String? = null,
    var create_date: String? = null,
    var id: Long = 0L,
    var image_urls: ImageUrls? = null,
    var is_bookmarked: Boolean? = null,
    var is_muted: Boolean? = null,
    var is_mypixiv_only: Boolean? = null,
    var is_original: Boolean? = null,
    var is_x_restricted: Boolean? = null,
    var page_count: Int? = null,
    var restrict: Int? = null,
    var series: Series? = null,
    var tags: List<Tag>? = null,
    var text_length: Int? = null,
    var title: String? = null,
    var total_bookmarks: Int? = null,
    var total_comments: Int? = null,
    var total_view: Int? = null,
    var user: User? = null,
    var visible: Boolean? = null,
    var x_restrict: Int? = null,
    var novel_ai_type: Int = 0,
    // Legacy/client fields retained under their original Gson keys.
    var coverUrl: String? = null,
    var viewable: Boolean = false,
    var contentOrder: String? = null,
    var isLocalSaved: Boolean = false,
    var is_concluded: Boolean = false,
    var content_count: Int = 0,
    var total_character_count: Int = 0,
    var display_text: String? = null,
    @Transient var trendingScore: Float? = null,
) : Serializable, ModelObject, Starable {

    override val objectUniqueId: Long get() = id
    override val objectType: Int get() = ObjectSpec.KNovel

    override fun getItemID(): Int = id.toInt()
    override fun setItemID(id: Int) {
        this.id = id.toLong()
    }

    override fun isItemStared(): Boolean = is_bookmarked == true
    override fun setItemStared(isLiked: Boolean) {
        is_bookmarked = isLiked
    }

    fun isCreatedByAI(): Boolean = novel_ai_type == IllustAIType.CreatedByAI

    val tagNames: List<String> get() = tags?.mapNotNull { it.name } ?: emptyList()

    val tagString: String get() = tags?.joinToString(separator = "") { "*#${it.name}," } ?: ""

    /** app-api 没给旧 coverUrl 字段时，从小说缩略图里选最大尺寸。 */
    fun resolvedCoverUrl(): String? =
        coverUrl ?: image_urls?.let { it.large ?: it.medium ?: it.square_medium }

    fun withBookmarked(bookmarked: Boolean): Novel =
        if (is_bookmarked == bookmarked) this else copy(is_bookmarked = bookmarked)

    fun withMuted(muted: Boolean): Novel =
        if (is_muted == muted) this else copy(is_muted = muted)

    fun withTrendingScore(score: Float?): Novel =
        if (trendingScore == score) this else copy(trendingScore = score)
}
