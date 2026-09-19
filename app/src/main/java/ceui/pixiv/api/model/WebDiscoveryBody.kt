package ceui.pixiv.api.model

import ceui.loxia.ImageUrls
import ceui.loxia.Tag
import ceui.loxia.User
import java.io.Serializable

data class WebDiscoveryBody(
    val thumbnails: WebDiscoveryThumbnails? = null,
) : Serializable

data class WebDiscoveryThumbnails(
    val illust: List<WebDiscoveryArtwork>? = null,
) : Serializable

/** 官网缩略图是精简作品；没有原图和完整关注态，不能当作 detail 写进 ObjectPool。 */
data class WebDiscoveryArtwork(
    val id: Long = 0L,
    val title: String? = null,
    val illustType: Int = 0,
    val xRestrict: Int = 0,
    val aiType: Int = 0,
    val url: String? = null,
    val tags: List<String>? = null,
    val userId: Long = 0L,
    val userName: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val pageCount: Int = 0,
    val createDate: String? = null,
    val profileImageUrl: String? = null,
    val bookmarkData: Any? = null,
    val isMasked: Boolean = false,
) : Serializable {
    fun toIllust(): Illust {
        val thumbnail = url.orEmpty()
        val path = IMAGE_PATH.find(thumbnail)?.groupValues?.get(1)
        val medium = path?.let { "https://i.pximg.net/c/540x540_70/img-master/img/${it}_master1200.jpg" }
            ?: thumbnail
        return Illust(
            id = id,
            title = title.orEmpty(),
            visible = !isMasked,
            width = width,
            height = height,
            page_count = pageCount.coerceAtLeast(1),
            x_restrict = xRestrict,
            illust_ai_type = aiType,
            create_date = createDate,
            is_bookmarked = bookmarkData != null,
            type = when (illustType) {
                1 -> "manga"
                2 -> "ugoira"
                else -> "illust"
            },
            image_urls = ImageUrls(
                medium = medium,
                large = path?.let { "https://i.pximg.net/c/600x1200_90_webp/img-master/img/${it}_master1200.jpg" }
                    ?: thumbnail,
                square_medium = thumbnail,
            ),
            user = User(
                id = userId,
                name = userName.orEmpty(),
                profile_image_urls = profileImageUrl?.let { ImageUrls(medium = it, px_170x170 = it) },
            ),
            tags = tags.orEmpty().map { Tag(name = it) },
        )
    }

    companion object {
        private val IMAGE_PATH = Regex("/img/(.+?)_(?:square|custom|master)1200\\.\\w+")
    }
}
