package ceui.loxia

import ceui.lisa.model.ListTrendingtag
import ceui.lisa.models.NullResponse
import ceui.lisa.utils.Params
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface API {

    // 举报流程已随官方客户端迁到 v2 + 动态 topic-list：旧 v1/illust/report 用固定
    // type_of_problem 字符串，服务端早已不认，一律 403。见 getIllustReportTopicList/postIllustReport。
    @GET("/v1/illust/report/topic-list")
    suspend fun getIllustReportTopicList(): IllustReportTopicListResponse

    @FormUrlEncoded
    @POST("/v2/illust/report")
    suspend fun postIllustReport(
        @Field("illust_id") illust_id: Long,
        @Field("topic_id") topic_id: Int,
        @Field("description") description: String
    ): NullResponse

    @FormUrlEncoded
    @POST("/v1/user/follow/add")
    suspend fun postFollow(
        @Field("user_id") user_id: Long,
        @Field("restrict") followType: String
    )

    @FormUrlEncoded
    @POST("/v1/user/follow/delete")
    suspend fun postUnFollow(
        @Field("user_id") user_id: Long
    )

    @FormUrlEncoded
    @POST("/v2/illust/bookmark/add")
    suspend fun postBookmark(
        @Field("illust_id") illust_id: Long,
        @Field("restrict") restrict: String = Params.TYPE_PUBLIC
    )

    /**
     * 带收藏夹标签的收藏。与 [postBookmark] 是同一个端点，只是多带 `tags[]` ——
     * 端点相同意味着它们对同一作品是互相覆盖的，不是叠加，所以队列里共用同一个 dedupeKey。
     *
     * 标签用 `List<String>` 而不是 legacy 那样的 `vararg`：payload 从 json 反序列化回来就是
     * 列表，摊成 vararg 只是为了迁就 [ceui.lisa.http.AppApi] 的旧签名。
     */
    @FormUrlEncoded
    @POST("/v2/illust/bookmark/add")
    suspend fun postBookmarkWithTags(
        @Field("illust_id") illust_id: Long,
        @Field("restrict") restrict: String,
        @Field("tags[]") tags: List<String>
    )

    @FormUrlEncoded
    @POST("/v2/novel/bookmark/add")
    suspend fun addNovelBookmark(
        @Field("novel_id") novel_id: Long,
        @Field("restrict") followType: String
    )

    @FormUrlEncoded
    @POST("/v2/novel/bookmark/add")
    suspend fun addNovelBookmarkWithTags(
        @Field("novel_id") novel_id: Long,
        @Field("restrict") restrict: String,
        @Field("tags[]") tags: List<String>
    )

    @FormUrlEncoded
    @POST("/v1/novel/bookmark/delete")
    suspend fun removeNovelBookmark(
        @Field("novel_id") novel_id: Long
    )

    // pixiv 原版书签（しおり/marker），page 为 1-based 的 [newpage] 分页序号；
    // 同一小说只有一个书签，重复 add 直接覆盖页码。
    @FormUrlEncoded
    @POST("/v1/novel/marker/add")
    suspend fun addNovelMarker(
        @Field("novel_id") novel_id: Long,
        @Field("page") page: Int
    )

    @FormUrlEncoded
    @POST("/v1/novel/marker/delete")
    suspend fun removeNovelMarker(
        @Field("novel_id") novel_id: Long
    )

    @FormUrlEncoded
    @POST("/v1/illust/bookmark/delete")
    suspend fun removeBookmark(
        @Field("illust_id") illust_id: Long
    )

    @GET("/v1/user/me/state")
    suspend fun getSelfProfile(): SelfProfile

    @GET("/v2/novel/series")
    suspend fun getNovelSeries(
        @Query("series_id") series_id: Long,
        @Query("last_order") last_order: Int? = null,
    ): NovelSeriesResp

    @GET("/v1/illust/series")
    suspend fun getIllustSeries(
        @Query("illust_series_id") series_id: Long,
        @Query("last_order") last_order: Int? = null,
    ): IllustSeriesResp

    @GET("/v1/illust/detail")
    suspend fun getIllust(
        @Query("illust_id") illust_id: Long
    ): SingleIllustResponse

    @GET("/v2/novel/detail")
    suspend fun getNovel(
        @Query("novel_id") novel_id: Long
    ): SingleNovelResponse

    @GET("/v2/illust/related")
    suspend fun getRelatedIllusts(
        @Query("illust_id") illust_id: Long,
    ): IllustResponse

    @GET("/v1/walkthrough/illusts")
    suspend fun getWalkthroughWorks(): IllustResponse

    /** 好P友(mypixiv,互关好友)的插画/漫画作品流。翻页走 nextUrl,与其它 feeds 一致。 */
    @GET("/v2/illust/mypixiv")
    suspend fun getNiceFriendIllust(): IllustResponse

    /**
     * 「动态」页:已关注画师的最新插画/漫画。
     * [restrict] 取 [ceui.lisa.utils.Params] 的 TYPE_ALL / TYPE_PUBLIC / TYPE_PRIVATE,
     * 对应页面上的 全部 / 公开 / 私人 筛选。
     */
    @GET("/v2/illust/follow")
    suspend fun getFollowingIllusts(
        @Query("restrict") restrict: String,
    ): IllustResponse

    /**
     * 已关注画师的最新小说：「动态」页的小说模式 + TemplateActivity「关注者的小说」独立页。
     * [restrict] 同 [getFollowingIllusts]。
     */
    @GET("/v1/novel/follow")
    suspend fun getFollowingNovels(
        @Query("restrict") restrict: String,
    ): NovelResponse

    @GET("/v1/{type}/recommended?include_ranking_illusts=false&include_privacy_policy=true&filter=for_ios")
    suspend fun getHomeData(
        @Path("type") type: String,
    ): HomeIllustResponse

    // 首页推荐 tab（feeds 版）：首屏要 ranking_illusts 做横向排行榜预览头
    @GET("/v1/{type}/recommended?include_ranking_illusts=true&include_privacy_policy=true&filter=for_ios")
    suspend fun getRecommendedWorksWithRanking(
        @Path("type") type: String,
    ): HomeIllustResponse

    @GET("/v1/novel/recommended")
    suspend fun getRecmdNovels(
        @Query("include_ranking_illusts") include_ranking_illusts: Boolean = false,
    ): NovelResponse

    // 推荐小说（feeds 版，替代 legacy RxJava AppApi.getRecmdNovel）：首屏带 ranking_novels
    // 排行榜预览头（对齐 legacy include_ranking_novels=true）。
    @GET("/v1/novel/recommended?include_privacy_policy=true&filter=for_ios&include_ranking_novels=true")
    suspend fun getRecommendedNovelsWithRanking(): NovelRecommendResponse

    @GET("/webview/v2/novel")
    suspend fun getNovelText(@Query("id") id: Long): ResponseBody

    @GET("/v1/user/illusts?filter=for_ios")
    suspend fun getUserCreatedIllusts(
        @Query("user_id") user_id: Long,
        @Query("type") type: String,
        /** 「跳转到某处」用的起始偏移(offset>0 时首屏从此拉,后续 next_url 照常翻);null=从头。 */
        @Query("offset") offset: Int? = null,
    ): IllustResponse

    @GET("/v1/user/bookmarks/illust?filter=for_ios")
    suspend fun getUserBookmarkedIllusts(
        @Query("user_id") user_id: Long,
        @Query("restrict") restrict: String,
        /** 按收藏标签过滤（「按标签筛选」/同义词词典跳转），null = 不过滤。 */
        @Query("tag") tag: String? = null,
    ): IllustResponse


    @GET("/v1/user/bookmarks/novel?filter=for_ios")
    suspend fun getUserBookmarkedNovels(
        @Query("user_id") user_id: Long,
        @Query("restrict") restrict: String,
        /** 按收藏标签过滤（「按标签筛选」/同义词词典跳转），null = 不过滤（对齐插画侧）。 */
        @Query("tag") tag: String? = null,
    ): NovelResponse

    @GET("/v1/user/novels")
    suspend fun getUserCreatedNovels(
        @Query("user_id") user_id: Long,
    ): NovelResponse

    @GET("/v1/novel/follow")
    suspend fun getFollowingCreatedNovels(
        @Query("restrict") restrict: String,
    ): NovelResponse


    @GET("/v2/user/detail?filter=for_ios")
    suspend fun getUserProfile(
        @Query("user_id") user_id: Long,
    ): UserResponse

    /** 画师开启「接受约稿(is_accept_request)」时的约稿方案列表(单页,无 next_url)。 */
    @GET("/v1/user/request-plans")
    suspend fun getUserRequestPlans(
        @Query("user_id") user_id: Long,
    ): ceui.pixiv.ui.user.UserRequestPlansResponse

    // filter=for_android 对齐 legacy AppApi.getFollowUser：本接口此前零调用方，迁「关注列表」页
    // (FollowUserFeedFragment) 时按 legacy 端点补齐，不影响其它页。
    @GET("/v1/user/following?filter=for_android")
    suspend fun getFollowingUsers(
        @Query("user_id") user_id: Long,
        @Query("restrict") restrict: String,
        /** 「跳页」用的起始偏移(offset>0 时首屏从此拉,后续 next_url 照常翻);null=从头。 */
        @Query("offset") offset: Int? = null,
    ): UserPreviewResponse

    @GET("/v1/user/follower?filter=for_ios")
    suspend fun getUserFans(
        @Query("user_id") user_id: Long,
    ): UserPreviewResponse

    // filter=for_android 对齐 legacy AppApi.getNiceFriend：本接口此前零调用方（声明了但从没被
    // 跑过），迁「好P友」页(NiceFriendFeedFragment)时才第一次激活它——补上 legacy 一直带着的
    // filter。pixiv 的 filter 决定 image_urls 返回哪套变体，漏掉它用户卡的 3 张预览图可能空白。
    // 它原先是 loxia 这组 user_preview 端点里唯一不带 filter 的（following=for_android /
    // follower=for_ios / recommended=for_ios / related=for_android），本就是个异类。
    /**
     * 「追更列表」——已追的漫画/小说**系列**（响应顶层字段就是 series，见 [WatchlistResponse]）。
     * legacy AppApi.getWatchlistManga/getWatchlistNovel 逐字对齐：路径之外不带任何 query。
     */
    @GET("v1/watchlist/manga")
    suspend fun getWatchlistManga(): WatchlistResponse

    @GET("v1/watchlist/novel")
    suspend fun getWatchlistNovel(): WatchlistResponse

    @GET("/v1/user/mypixiv?filter=for_android")
    suspend fun getUserPixivFriends(
        @Query("user_id") user_id: Long,
    ): UserPreviewResponse

    @GET("/v2/{type}/follow")
    suspend fun followUserPosts(
        @Path("type") type: String,
        @Query("restrict") restrict: String,
    ): IllustResponse

    @GET("/v1/user/recommended?filter=for_ios")
    suspend fun recommendedUsers(): UserPreviewResponse

    /** 相关用户（某画师的相关画师推荐，对齐 legacy AppApi.getRelatedUsers）。 */
    @GET("/v1/user/related?filter=for_android")
    suspend fun getRelatedUsers(
        @Query("seed_user_id") seed_user_id: Long,
    ): UserPreviewResponse

    // /v1/illust/ranking?mode=day_manga&filter=for_ios
    @GET("/v1/illust/ranking?filter=for_ios")
    suspend fun getRankingIllusts(
        @Query("mode") mode: String,
        // 指定日期看往期榜单（日期选择器）；null 时 Retrofit 不带该参数 = 最新一期
        @Query("date") date: String? = null,
    ): IllustResponse

    // 小说排行榜（feeds 版，替代 legacy RxJava AppApi.getRankNovel）：mode 见
    // RankNovelFeedFragment.NOVEL_MODES。legacy 带的是 filter=for_android，这里跟 loxia 侧
    // getRankingIllusts 统一成 for_ios（小说响应无 image_urls 变体差异，两者等价）。
    @GET("/v1/novel/ranking?filter=for_ios")
    suspend fun getRankingNovels(
        @Query("mode") mode: String,
        // 指定日期看往期榜单（日期选择器）；null 时 Retrofit 不带该参数 = 最新一期
        @Query("date") date: String? = null,
    ): NovelResponse

    // 最新作品（feeds 版，替代 legacy RxJava AppApi.getNewWorks）：全站最新投稿的插画/漫画。
    // content_type = "illust" | "manga"。
    @GET("/v1/illust/new?filter=for_ios")
    suspend fun getNewIllusts(
        @Query("content_type") contentType: String,
    ): IllustResponse

    // 最新小说（feeds 版，替代 legacy RxJava AppApi.getNewNovels）：全站最新投稿的小说。
    @GET("/v1/novel/new")
    suspend fun getNewNovels(): NovelResponse

    // 对齐 pixiv iOS app 8.6.5 实际调用——
    //   - 不再带 ?filter=for_ios（app-os: ios header 已经表态；image_urls 实测一致）
    //   - 不传 include_potential_violation_works（iOS 默认 false 会让 pixiv 隐藏被自动标记为
    //     「疑似违规」的作品，部分关键字会因此搜不到任何结果——#906；不传 = 服务端默认包含）
    @GET("/v1/search/popular-preview/illust")
    suspend fun popularPreview(
        @Query("word") word: String,
        @Query("sort") sort: String,
        // null = 不传——见 [ceui.pixiv.ui.search.v3.SearchTarget.toQueryValue]（#906）
        @Query("search_target") search_target: String?,
        @Query("merge_plain_keyword_results") merge_plain_keyword_results: Boolean,
        @Query("include_translated_tag_results") include_translated_tag_results: Boolean,
        @Query("search_ai_type") search_ai_type: Int = 0,
        @Query("bookmark_num_min") bookmark_num_min: Int? = null,
        @Query("bookmark_num_max") bookmark_num_max: Int? = null,
        @Query("tool") tool: String? = null,
        @Query("lang") lang: String? = null,
        @Query("start_date") start_date: String? = null,
        @Query("end_date") end_date: String? = null,
        @Query("ratio_pattern") ratio_pattern: String? = null,
        @Query("content_type") content_type: String? = null,
        @Query("width_min") width_min: Int? = null,
        @Query("width_max") width_max: Int? = null,
        @Query("height_min") height_min: Int? = null,
        @Query("height_max") height_max: Int? = null,
    ): IllustResponse

    @GET("/v1/search/popular-preview/novel")
    suspend fun popularPreviewNovel(
        @Query("word") word: String,
        @Query("sort") sort: String,
        // null = 不传——见 [ceui.pixiv.ui.search.v3.SearchTarget.toQueryValue]（#906）
        @Query("search_target") search_target: String?,
        @Query("merge_plain_keyword_results") merge_plain_keyword_results: Boolean,
        @Query("include_translated_tag_results") include_translated_tag_results: Boolean,
        @Query("search_ai_type") search_ai_type: Int = 0,
        @Query("bookmark_num_min") bookmark_num_min: Int? = null,
        @Query("bookmark_num_max") bookmark_num_max: Int? = null,
        @Query("genre") genre: Int? = null,
        @Query("lang") lang: String? = null,
        @Query("start_date") start_date: String? = null,
        @Query("end_date") end_date: String? = null,
        @Query("is_original_only") is_original_only: Boolean? = null,
        @Query("is_replaceable_only") is_replaceable_only: Boolean? = null,
        // 正文长度 3 单位（iOS pixiv 8.6.6 抓包确认）
        @Query("text_length_min") text_length_min: Int? = null,
        @Query("text_length_max") text_length_max: Int? = null,
        @Query("word_count_min") word_count_min: Int? = null,
        @Query("word_count_max") word_count_max: Int? = null,
        @Query("reading_time_min") reading_time_min: Int? = null,
        @Query("reading_time_max") reading_time_max: Int? = null,
    ): NovelResponse

    @GET("/v1/spotlight/articles?filter=for_ios")
    suspend fun pixivsionArticles(
        @Query("category") category: String,
    ): ArticlesResponse

    @GET("/v1/search/illust")
    suspend fun searchIllustManga(
        @Query("word") word: String,
        @Query("sort") sort: String,
        // null = 不传——见 [ceui.pixiv.ui.search.v3.SearchTarget.toQueryValue]（#906）
        @Query("search_target") search_target: String?,
        @Query("merge_plain_keyword_results") merge_plain_keyword_results: Boolean,
        @Query("include_translated_tag_results") include_translated_tag_results: Boolean,
        @Query("search_ai_type") search_ai_type: Int = 0,
        @Query("bookmark_num_min") bookmark_num_min: Int? = null,
        @Query("bookmark_num_max") bookmark_num_max: Int? = null,
        @Query("tool") tool: String? = null,
        @Query("lang") lang: String? = null,
        @Query("start_date") start_date: String? = null,
        @Query("end_date") end_date: String? = null,
        @Query("ratio_pattern") ratio_pattern: String? = null,
        @Query("content_type") content_type: String? = null,
        @Query("width_min") width_min: Int? = null,
        @Query("width_max") width_max: Int? = null,
        @Query("height_min") height_min: Int? = null,
        @Query("height_max") height_max: Int? = null,
    ): IllustResponse

    @GET("/v1/search/novel")
    suspend fun searchNovel(
        @Query("word") word: String,
        @Query("sort") sort: String,
        // null = 不传——见 [ceui.pixiv.ui.search.v3.SearchTarget.toQueryValue]（#906）
        @Query("search_target") search_target: String?,
        @Query("merge_plain_keyword_results") merge_plain_keyword_results: Boolean,
        @Query("include_translated_tag_results") include_translated_tag_results: Boolean,
        @Query("search_ai_type") search_ai_type: Int = 0,
        @Query("bookmark_num_min") bookmark_num_min: Int? = null,
        @Query("bookmark_num_max") bookmark_num_max: Int? = null,
        @Query("genre") genre: Int? = null,
        @Query("lang") lang: String? = null,
        @Query("start_date") start_date: String? = null,
        @Query("end_date") end_date: String? = null,
        @Query("is_original_only") is_original_only: Boolean? = null,
        @Query("is_replaceable_only") is_replaceable_only: Boolean? = null,
        // 正文长度 3 单位（iOS pixiv 8.6.6 抓包确认）
        @Query("text_length_min") text_length_min: Int? = null,
        @Query("text_length_max") text_length_max: Int? = null,
        @Query("word_count_min") word_count_min: Int? = null,
        @Query("word_count_max") word_count_max: Int? = null,
        @Query("reading_time_min") reading_time_min: Int? = null,
        @Query("reading_time_max") reading_time_max: Int? = null,
    ): NovelResponse

    @GET("/v1/search/options")
    suspend fun searchOptions(
        @Query("word") word: String,
        @Query("search_target") search_target: String = "partial_match_for_tags",
        @Query("merge_plain_keyword_results") merge_plain_keyword_results: Boolean = true,
        @Query("include_translated_tag_results") include_translated_tag_results: Boolean = true,
        @Query("search_ai_type") search_ai_type: Int = 0,
    ): ceui.pixiv.ui.search.v3.SearchOptionsResponse


    @GET("/v1/search/user?filter=for_ios")
    suspend fun searchUser(
        @Query("word") word: String,
    ): UserPreviewResponse


    // :path	/v1/trending-tags/?filter=for_ios
    @GET("/v1/trending-tags/{type}?filter=for_ios")
    suspend fun trendingTags(
        @Path("type") type: String,
    ): TrendingTagsResponse


    @GET("/v3/illust/comments")
    suspend fun getIllustComments(
        @Query("illust_id") illust_id: Long,
    ): CommentResponse

    @GET("/v3/novel/comments")
    suspend fun getNovelComments(
        @Query("novel_id") novel_id: Long,
    ): CommentResponse

    @GET("/v2/{type}/comment/replies")
    suspend fun getIllustReplyComments(
        @Path("type") type: String,
        @Query("comment_id") comment_id: Long,
    ): CommentResponse

    @FormUrlEncoded
    @POST("/v1/illust/comment/add")
    suspend fun postIllustComment(
        @Field("illust_id") illust_id: Long,
        @Field("comment") comment: String,
        @Field("parent_comment_id") parent_comment_id: Long? = null,
        @Field("stamp_id") stamp_id: Long? = null,
    ): PostCommentResponse

    @FormUrlEncoded
    @POST("/v1/novel/comment/add")
    suspend fun postNovelComment(
        @Field("novel_id") novel_id: Long,
        @Field("comment") comment: String,
        @Field("parent_comment_id") parent_comment_id: Long? = null,
        @Field("stamp_id") stamp_id: Long? = null,
    ): PostCommentResponse

    // 评论「表情贴图」目录:官方常驻 40 个贴纸(stamp_id + stamp_url),发评论时把
    // comment 留空、改传 stamp_id 即可(见 postIllustComment/postNovelComment)。
    @GET("/v1/stamps")
    suspend fun getStamps(): StampsResponse

    @FormUrlEncoded
    @POST("/v1/{type}/comment/delete")
    suspend fun deleteComment(
        @Path("type") type: String,
        @Field("comment_id") comment_id: Long,
    )

    @GET
    suspend fun generalGet(@Url url: String): ResponseBody

    @GET("/v2/search/autocomplete?merge_plain_keyword_results=true")
    suspend fun searchAutocomplete(@Query("word") word: String): ListTrendingtag

    @GET("/idp-urls")
    suspend fun getIdpUrls(): IdpUrlsResponse

    @GET("/v1/notification/list")
    suspend fun getNotificationList(): NotificationListResponse

    @GET("/v1/notification/view-more")
    suspend fun getNotificationViewMore(
        @Query("notification_id") notification_id: Long,
    ): NotificationListResponse

    @GET("/v1/info/latest")
    suspend fun getInfoLatest(): InfoLatestResponse

    @GET("/v1/info/list")
    suspend fun getInfoList(
        @Query("cid") cid: Int,
    ): InfoListResponse
}