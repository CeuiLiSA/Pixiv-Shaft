package ceui.lisa.network

import com.google.gson.JsonObject
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface ShaftApiV2 {

    data class HealthResponse(
        val ok: Boolean,
        val service: String,
        val ts: Long,
        val uptimeSec: Long,
    )

    @GET("health")
    suspend fun health(): HealthResponse

    /** Demo: 路径不存在,服务端返回 404 → Retrofit 抛 [retrofit2.HttpException]。 */
    @GET("does-not-exist")
    suspend fun probe404(): HealthResponse

    /** Demo: /ping 实际返回 200 plain text "pong",硬塞给 Gson 当 HealthResponse 解析必失败 → [com.google.gson.JsonSyntaxException]。 */
    @GET("ping")
    suspend fun pingAsHealth(): HealthResponse

    /**
     * 站长推荐数据源。每个 item 的 `bean` 字段直接就是 Pixiv 端的 IllustsBean（illust /
     * manga）或 NovelBean（novel）JSON，可以丢给 Gson 反序列化复用现成渲染管线。
     *
     * - type: illust | manga | novel
     * - window: day | week | month
     * - sort: score（加权） | bookmark（纯收藏数倒序）
     * - includeMeta=1 时服务端会过滤掉还没有客户端上传过 payload 的 id，保证返回的每个 item 都能渲染
     *
     * 翻页:首屏调本接口拿 offset=0,后续走 [trendingWorksByUrl] 喂服务端给的 `next_url`
     * (绝对 URL,已经带好原始 query 参数 + 新 offset),server 端 null 即榜单到底。
     */
    @GET("api/v1/trending/works")
    suspend fun trendingWorks(
        @Query("type") type: String,
        @Query("window") window: String = "week",
        @Query("limit") limit: Int = 60,
        @Query("sort") sort: String = "bookmark",
        @Query("include_meta") includeMeta: Int = 1,
        @Query("offset") offset: Int = 0,
    ): TrendingWorksResponse

    /** 翻页专用:直接打 server 返回的 `next_url`(绝对 URL),避免客户端自己算 offset。 */
    @GET
    suspend fun trendingWorksByUrl(@Url url: String): TrendingWorksResponse

    data class TrendingWorksResponse(
        val type: String,
        val window: String,
        val limit: Int,
        val sort: String,
        val computed_at: Long,
        val items: List<TrendingWorkItem>,
        /** offset/total/next_url 是 v2 新增字段,nullable 是为了兼容尚未升级的服务端。 */
        val offset: Int? = null,
        val total: Int? = null,
        val next_url: String? = null,
    )

    data class TrendingWorkItem(
        val target_id: Long,
        val bookmark_count: Int,
        val unbookmark_count: Int,
        val download_count: Int,
        val unique_clients: Int,
        val score: Double,
        val computed_at: Long,
        /** 完整 IllustsBean / NovelBean JSON，仅 include_meta=1 时存在。 */
        val bean: JsonObject?,
        /** 仅 most-viewed 榜返回:该作 pixiv 总浏览数(其它榜单无此字段,默认 0)。 */
        val view_count: Int = 0,
    )

    /**
     * 当前最热 — 「现在正在被人收藏的作品」。item.bean 同 trending 一样带完整 payload。
     * 翻页同 trending:首屏 offset=0,后续走 [recentWorksByUrl] 喂 server 的 next_url。
     *
     * [window] 可选,day | week | month:
     *   - null  → 实时流:按最近一次 bookmark 事件倒序、server 端按作品去重(原行为)。
     *   - 给定  → 实时日/周/月榜:只统计窗口内 bookmark,按窗口内 bookmark_count 降序。
     * 仍只看 bookmark 事件,不是加权榜 → server 端 score 恒为 0。Retrofit 对 null
     * @Query 不发该参数,所以不传 window 即旧契约,向后兼容。
     */
    @GET("api/v1/recent/works")
    suspend fun recentWorks(
        @Query("type") type: String,
        @Query("limit") limit: Int = 60,
        @Query("offset") offset: Int = 0,
        @Query("window") window: String? = null,
    ): RecentWorksResponse

    /** 翻页专用:直接打 server 返回的 `next_url`(绝对 URL,已带 window)。 */
    @GET
    suspend fun recentWorksByUrl(@Url url: String): RecentWorksResponse

    /**
     * 复用 [TrendingWorkItem] 的 item 形状(只读 target_id / bookmark_count / bean)。
     * 当前最热不是加权榜,server 端 score 恒为 0,客户端 score pill 自动隐藏;窗口模式
     * 下热度看 bookmark_count。window/sort 是 server 回显,nullable 兼容旧服务端。
     */
    data class RecentWorksResponse(
        val type: String,
        val limit: Int,
        val items: List<TrendingWorkItem>,
        val window: String? = null,
        val sort: String? = null,
        val offset: Int? = null,
        val total: Int? = null,
        val next_url: String? = null,
    )

    /**
     * 发现页首屏聚合。一次请求拿回两条 shaft-api-v2 货架(本月收藏 / 当前最热),替掉之前各打
     * 一枪的两个来回。每条 shelf.items 复用 [TrendingWorkItem] 形状,item.bean 直接是完整
     * IllustsBean JSON。只回每条首屏 top-N(货架在 tab 里是截断预览);「查看全部」仍走各自分页
     * 接口。整包服务端 60s 缓存,下拉刷新照拉(慢变量,tab 首屏无需实时)。
     */
    @GET("api/v1/discover")
    suspend fun discover(): DiscoverResponse

    data class DiscoverResponse(
        val computed_at: Long,
        val site: DiscoverShelf,
        val recent: DiscoverShelf,
    )

    data class DiscoverShelf(
        val items: List<TrendingWorkItem>,
    )

    /**
     * 画师收藏总榜 —— 按画师全部作品的 pixiv 总收藏数求和排名(含 R-18)。服务端回 pixiv
     * user_previews 形状。user / illusts 都是原始 pixiv JSON(JsonObject),由 [ArtistRankRepo]
     * 用 Shaft.sGson 反序列化成 UserBean / IllustsBean(和 HotWorksFeed 一致,不用 Retrofit
     * 默认 Gson),拼成 ListUser 复用现成「画师 + 3 预览图」列表。翻页跟随 next_url。
     */
    @GET("api/v1/discover/artists")
    suspend fun discoverArtists(
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
        /** total=总收藏榜 / avg=平均收藏榜(质量派,作品≥20)。 */
        @Query("sort") sort: String = "total",
    ): ArtistRankResponse

    /** 翻页专用:直接打 server 返回的 `next_url`(绝对 URL,已带 sort)。 */
    @GET
    suspend fun discoverArtistsByUrl(@Url url: String): ArtistRankResponse

    /**
     * 全站浏览量榜 —— 单作按 pixiv 总浏览数排(含 R-18)。item 复用 [TrendingWorkItem]
     * (target_id/bookmark_count/bean;服务端另带 view_count)。翻页跟随 next_url。
     */
    @GET("api/v1/discover/most-viewed")
    suspend fun mostViewed(
        @Query("type") type: String = "illust",
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
    ): MostViewedResponse

    /** 翻页专用:直接打 server 返回的 `next_url`(绝对 URL)。 */
    @GET
    suspend fun mostViewedByUrl(@Url url: String): MostViewedResponse

    data class MostViewedResponse(
        val type: String,
        val limit: Int,
        val items: List<TrendingWorkItem>,
        val offset: Int? = null,
        val next_url: String? = null,
    )

    /**
     * 全站收藏榜 —— 单作按 pixiv 总收藏数排(含 R-18)。item 复用 [TrendingWorkItem]
     * (target_id/bookmark_count/bean;score 恒 0)。翻页跟随 next_url。
     *
     * 三个可选筛选,可任意组合(Retrofit 对 null @Query 不发该参数,所以不传即无筛选):
     * - [ai]   only=只看 AI 生成 / exclude=只看非 AI。⚠️ **novel 没有 illust_ai_type 字段**
     *          (pixiv 的 NovelBean 里就没有),传了会 400 `ai_filter_unsupported_for_novel`
     *          —— 服务端刻意不静默返回空,免得被读成「小说里没有 AI 作品」。
     * - [year] 创作年份(4 位)。可选年份见 [discoverYears]。
     * - [q]    标题 **或** 画师名子串模糊搜索,大小写不敏感。
     *
     * ⚠️ 收藏数是服务端 first-write-wins 的**定格值**(我们的用户首次看到该作时的数字),
     * 不是实时 —— 这是「历史殿堂榜」。要实时热度用 [trendingWorks] / [recentWorks]。
     */
    @GET("api/v1/discover/most-bookmarked")
    suspend fun mostBookmarked(
        @Query("type") type: String = "illust",
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
        @Query("ai") ai: String? = null,
        @Query("year") year: String? = null,
        @Query("q") q: String? = null,
        /** **精确** tag 名(服务端原文,不是 translated;区分大小写)。可选值见 [discoverTags]。 */
        @Query("tag") tag: String? = null,
    ): MostBookmarkedResponse

    /** 翻页专用:直接打 server 返回的 `next_url`(绝对 URL,已带 ai/year/q/tag)。 */
    @GET
    suspend fun mostBookmarkedByUrl(@Url url: String): MostBookmarkedResponse

    data class MostBookmarkedResponse(
        val type: String,
        val limit: Int,
        val items: List<TrendingWorkItem>,
        val offset: Int? = null,
        val next_url: String? = null,
        /** 服务端回显的筛选值,没传就是 null。 */
        val ai: String? = null,
        val year: String? = null,
        val tag: String? = null,
    )

    /**
     * 「标签专区」的标签选择器数据源:热门标签按库内作品数降序(tag 原文 + 官方翻译 + 数量)。
     * `tag` 是**原文**,直接透传给 [mostBookmarked] 的 tag 参数;UI 展示优先用 translated。
     * [q] 子串模糊筛,原文/翻译都参与匹配(搜「碧蓝」能出「ブルーアーカイブ」)。
     * complete=false 表示服务端衍生表还在回填(部署后 ~15 分钟),计数是部分值 —— 榜单
     * 内容照常可用,只是数字偏小,客户端无需特殊处理。
     */
    @GET("api/v1/discover/tags")
    suspend fun discoverTags(
        @Query("type") type: String = "illust",
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
        @Query("q") q: String? = null,
    ): TagsResponse

    data class TagsResponse(
        val type: String,
        val complete: Boolean = true,
        val tags: List<TagBucket> = listOf(),
        val next_url: String? = null,
    )

    data class TagBucket(
        /** 服务端 enum 语义的原文 tag 名,透传给 [mostBookmarked],别本地化。 */
        val tag: String = "",
        /** pixiv 官方翻译(多为中文/英文),可空;展示优先用它。 */
        val translated: String? = null,
        val count: Int = 0,
    )

    /**
     * 壁纸榜 —— 只含 illust,按 pixiv 总收藏数排(含 R-18)。入选是服务端双闸:比例
     * (desktop 横图 w/h≥1.5 且 w≥1200;phone 竖图 h/w≥5/3 且 h≥1600)**且**语义命中
     * (tag/标题/简介带壁纸或风景词 —— 纯比例合格的图 95% 只是画得宽/长,不是壁纸)。
     * item 复用 [TrendingWorkItem](target_id/bookmark_count/bean;服务端另带 width/height,
     * bean 里本来就有,客户端不需要单独字段)。翻页跟随 next_url。
     */
    @GET("api/v1/discover/wallpapers")
    suspend fun wallpapers(
        @Query("screen") screen: String = "phone",
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
    ): WallpapersResponse

    /** 翻页专用:直接打 server 返回的 `next_url`(绝对 URL,已带 screen)。 */
    @GET
    suspend fun wallpapersByUrl(@Url url: String): WallpapersResponse

    data class WallpapersResponse(
        val screen: String,
        val limit: Int,
        val items: List<TrendingWorkItem>,
        val offset: Int? = null,
        val next_url: String? = null,
    )

    /**
     * 「年代榜」的年份选择器数据源:有哪些年、每年多少作品(年份降序)。
     * `year` 是**字符串**,直接透传给 [mostBookmarked] 的 year 参数。
     * 分布极度倾斜(2026 年占 56%,2007 年只有几十个),所以 UI 要把 count 显出来。
     */
    @GET("api/v1/discover/years")
    suspend fun discoverYears(
        @Query("type") type: String = "illust",
    ): YearsResponse

    data class YearsResponse(
        val type: String,
        val years: List<YearBucket> = listOf(),
    )

    data class YearBucket(
        val year: String = "",
        val count: Int = 0,
    )

    data class ArtistRankResponse(
        val user_previews: List<ArtistPreviewItem> = listOf(),
        val next_url: String? = null,
    )

    data class ArtistPreviewItem(
        /** 原始 pixiv user JSON(id/name/account/profile_image_urls)。 */
        val user: JsonObject?,
        /** 该画师 top-N 代表作的完整 pixiv illust JSON。 */
        val illusts: List<JsonObject> = listOf(),
        val user_id: Long = 0,
        val total_bookmarks: Long = 0,
        val work_count: Int = 0,
    )

    /**
     * 当前客户端自己的操作日志。client_id 是本地生成的 sha256(UUID)，只能查到自己的事件。
     * - eventType: null=全部；bookmark / unbookmark / download / follow / unfollow
     * - before: 上一页最后一条的 id（服务端按 id DESC 排），首页传 null
     * 每条 item.meta 直接是当时上报的 IllustsBean / NovelBean / UserBean JSON。
     */
    @GET("api/v1/events/history")
    suspend fun eventsHistory(
        @Query("client_id") clientId: String,
        @Query("limit") limit: Int = 50,
        @Query("event_type") eventType: String? = null,
        @Query("before") before: Long? = null,
    ): EventsHistoryResponse

    data class EventsHistoryResponse(
        val client_id: String,
        val limit: Int,
        val event_type: String?,
        val items: List<EventHistoryItem>,
        val next_before: Long?,
    )

    data class EventHistoryItem(
        val id: Long,
        val ts: Long,
        val event_type: String,
        val target_type: String,
        val target_id: Long,
        val platform: String?,
        val channel: String?,
        val app_version: String?,
        val meta: JsonObject?,
    )

    // ── Plaza ─────────────────────────────────────────────────────────────────
    // 注意:write 请求 body 必须保持 canonical 形态(text/refs key 顺序固定 + 无空格,
    // 见 docs/shaft-plaza-api-android.md §1)。所以这里收 RequestBody 而不是 DTO
    // —— 让 [ShaftApiV2Client] 手拼 canonical wire body 之后传进来,避免 Gson 介入。
    // 高层入口 (含签名 / cache / SharedFlow) 见 [ShaftApiV2Client]。

    @POST("api/v1/plaza/posts")
    suspend fun createPlazaPost(@Body body: RequestBody): PlazaPost

    @GET("api/v1/plaza/posts")
    suspend fun listPlazaPosts(
        @Query("limit") limit: Int = 20,
        @Query("before") before: Long? = null,
        @Query("viewer_uid") viewerUid: String? = null,
        @Query("viewer_ts") viewerTs: String? = null,
        @Query("viewer_sig") viewerSig: String? = null,
    ): PlazaFeedResponse

    @GET("api/v1/plaza/posts/{id}")
    suspend fun getPlazaPost(
        @Path("id") id: Long,
        @Query("viewer_uid") viewerUid: String? = null,
        @Query("viewer_ts") viewerTs: String? = null,
        @Query("viewer_sig") viewerSig: String? = null,
    ): PlazaPost

    /** Retrofit 默认不允许 DELETE 带 body,用 @HTTP 强制开放;body 需要 Content-Type: application/json。 */
    @HTTP(method = "DELETE", path = "api/v1/plaza/posts/{id}", hasBody = true)
    suspend fun deletePlazaPost(
        @Path("id") id: Long,
        @Body body: RequestBody,
    ): PlazaDeleteResponse

    @GET("api/v1/plaza/users/{uid}/posts")
    suspend fun listUserPlazaPosts(
        @Path("uid") uid: Long,
        @Query("limit") limit: Int = 20,
        @Query("before") before: Long? = null,
        @Query("viewer_uid") viewerUid: String? = null,
        @Query("viewer_ts") viewerTs: String? = null,
        @Query("viewer_sig") viewerSig: String? = null,
    ): PlazaUserPostsResponse

    /**
     * "我的点赞" 列表。HMAC 鉴权 —— path uid 必须 == sig uid,所以只能拉
     * 自己的列表(server 强制)。cursor `before` 是 **like_id** 不是 post.id。
     * 每个 item 额外带 `liked_at` 毫秒时间戳。
     */
    @GET("api/v1/plaza/users/{uid}/likes")
    suspend fun listMyPlazaLikes(
        @Path("uid") uid: Long,
        @Query("ts") ts: String,
        @Query("sig") sig: String,
        @Query("limit") limit: Int = 20,
        @Query("before") before: Long? = null,
    ): PlazaLikesResponse

    // ── Likes / Comments ─────────────────────────────────────────────────
    // 同样 write 请求要 canonical body,wire 由 ShaftApiV2Client 手拼。

    @POST("api/v1/plaza/posts/{id}/like")
    suspend fun likePlazaPost(
        @Path("id") id: Long,
        @Body body: RequestBody,
    ): PlazaLikeResponse

    @HTTP(method = "DELETE", path = "api/v1/plaza/posts/{id}/like", hasBody = true)
    suspend fun unlikePlazaPost(
        @Path("id") id: Long,
        @Body body: RequestBody,
    ): PlazaLikeResponse

    @POST("api/v1/plaza/posts/{id}/comments")
    suspend fun createPlazaComment(
        @Path("id") id: Long,
        @Body body: RequestBody,
    ): PlazaComment

    @GET("api/v1/plaza/posts/{id}/comments")
    suspend fun listPlazaComments(
        @Path("id") id: Long,
        @Query("limit") limit: Int = 20,
        @Query("before") before: Long? = null,
    ): PlazaCommentsResponse

    @HTTP(method = "DELETE", path = "api/v1/plaza/comments/{cid}", hasBody = true)
    suspend fun deletePlazaComment(
        @Path("cid") cid: Long,
        @Body body: RequestBody,
    ): PlazaDeleteResponse
}
