package ceui.loxia


data class PixivHtmlObject(
    val viewerVersion: String? = null,
    val isV2: Boolean? = null,
    val userLang: String? = null,
    val novel: WebNovel? = null,
    val isOwnWork: Boolean? = null,
    val authorDetails: AuthorDetails? = null
)

data class AuthorDetails(
    val userId: Long? = null,
    val userName: String? = null,
    val isFollowed: Boolean? = null,
    val isBlocked: Boolean? = null,
    val profileImg: ImageUrls? = null
)
