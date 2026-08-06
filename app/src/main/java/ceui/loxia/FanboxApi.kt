package ceui.loxia

import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * pixiv FANBOX(api.fanbox.cc)。
 *
 * 认证走 **cookie**,不是 OAuth —— FANBOX 和 app-api 是两套体系,主 app 的 Bearer 在这里无效。
 * 实测只要带上 WebView 里那份 `FANBOXSESSID` 就是完整登录态(`post.listHome`、
 * `plan.listSupporting`、`bell.countUnread` 全 200),所以 [FanboxHeaderInterceptor] 直接从
 * `CookieManager` 取,不另存一份。用户没在 WebView 里登录过 FANBOX 时 [postListHome] 返回 401。
 *
 * 另一个必需项是 `Origin: https://www.fanbox.cc` —— 不带这个头一律 400,和登录态无关。
 *
 * 注意 `post.info` / `post.get` 都拿不到正文块(前者恒 403,后者的 post 对象没有 body 字段),
 * 所以这里只做列表,帖子正文仍然要落回 WebView。
 */
interface FanboxApi {

    /** 首页「投稿」流。需要登录态;未登录 401。 */
    @GET("post.listHome")
    suspend fun postListHome(@Query("limit") limit: Int = 10): FanboxPostListResponse

    /** 翻页:服务端在 [FanboxPostList.nextUrl] 里给的是 api.fanbox.cc 绝对 URL,照着打即可。 */
    @GET
    suspend fun postListHomeByUrl(@Url url: String): FanboxPostListResponse

    /**
     * 首页「为您推荐的创作者」。未登录也能拿到(内容会变成通用推荐)。
     * 响应里**没有翻页游标**,就是单页。
     */
    @GET("creator.listRecommended")
    suspend fun creatorListRecommended(@Query("limit") limit: Int = 10): FanboxCreatorListResponse
}

data class FanboxPostListResponse(
    val body: FanboxPostList?
)

data class FanboxPostList(
    val items: List<FanboxPost>?,
    val nextUrl: String?,
)

/**
 * 一条投稿。**列表接口不返回正文**,[excerpt] 常常也是空串 —— 付费墙后的帖子
 * ([isRestricted] 为 true)服务端只给到封面和标题。
 */
data class FanboxPost(
    val id: String,
    val title: String?,
    val feeRequired: Int,
    val publishedDatetime: String?,
    val tags: List<String>?,
    val likeCount: Int,
    val commentCount: Int,
    val isRestricted: Boolean,
    val user: FanboxUser?,
    val creatorId: String?,
    val hasAdultContent: Boolean,
    val cover: FanboxCover?,
    val excerpt: String?,
)

data class FanboxUser(
    val userId: String?,
    val name: String?,
    val iconUrl: String?,
)

data class FanboxCover(
    val type: String?,
    val url: String?,
)

data class FanboxCreatorListResponse(
    val body: FanboxCreatorList?
)

data class FanboxCreatorList(
    val creators: List<FanboxCreator>?
)

/**
 * 推荐位上的创作者。[profileItems] 是主页展示图,网页版拿它铺卡片顶部那一排缩略图;
 * [category] 服务端经常给 null,不要拿它当必有字段。
 */
data class FanboxCreator(
    val user: FanboxUser?,
    val creatorId: String?,
    val description: String?,
    val hasAdultContent: Boolean,
    val coverImageUrl: String?,
    val profileItems: List<FanboxProfileItem>?,
    val isFollowed: Boolean,
    val isSupported: Boolean,
    val hasPublishedPost: Boolean,
    val category: String?,
)

data class FanboxProfileItem(
    val id: String?,
    val type: String?,
    val imageUrl: String?,
    val thumbnailUrl: String?,
)
