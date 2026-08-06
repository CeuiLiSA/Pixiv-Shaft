package ceui.loxia

import retrofit2.http.GET

/**
 * pixiv COMIC(comic.pixiv.net)官方漫画。
 *
 * 认证直接复用 pixiv 主 app 的 OAuth access token —— 实测 comic.pixiv.net 收下
 * [HeaderInterceptor] 原样发出的那套头(PixivIOSApp UA + Bearer),不需要 comic 自己那条
 * PKCE 登录流(comic-api.pixiv.net/web/v1/login?client=comic_ios),也不校验 x-client-hash。
 * 所以这个 service 只是换了个 baseUrl 的 app-api,别为它再造一套登录。
 *
 * 未登录(不带 Bearer)一律 403,首页也不例外。
 */
interface ComicApi {

    /** 首页:轮播 banner + 「更新された作品」。无分页参数,一次性返回全部。 */
    @GET("api/app/top/v8")
    suspend fun getComicTop(): ComicTopResponse
}

data class ComicTopResponse(
    val data: ComicTopData?
)

data class ComicTopData(
    val banners: List<ComicBanner>?,
    val recent_updated_official_works: List<ComicWork>?,
)

/**
 * 首页顶部轮播。[url] 是 comic 站内作品页(https://comic.pixiv.net/works/{id}),
 * [in_app] 为 true 表示官方端在应用内打开而不是丢给浏览器。
 */
data class ComicBanner(
    val id: Long,
    val image_url: String?,
    val url: String?,
    val width: Int,
    val height: Int,
    val in_app: Boolean,
)

/**
 * 一部官方连载作品。[author] 是服务端拼好的一整串(形如「日野晶,原作：花柄」),不要再拆。
 * [thumbnail_image_url] 是竖版封面(宫格用),[main_image_url] 是横版主图。
 */
data class ComicWork(
    val id: Long,
    val title: String?,
    val author: String?,
    val like_count: Long,
    val is_new_work: Boolean,
    val pixiv_comic_badge: Boolean,
    val last_story_read_start_at: Long,
    val thumbnail_image_url: String?,
    val main_image_url: String?,
    val stories_count: Int,
)
