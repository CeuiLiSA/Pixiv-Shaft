package ceui.lisa.http

import ceui.lisa.model.ListBookmarkTag
import ceui.lisa.model.ListIllust
import ceui.lisa.model.ListMangaSeries
import ceui.lisa.model.ListNovel
import ceui.lisa.model.ListNovelMarkers
import ceui.lisa.model.ListNovelOfSeries
import ceui.lisa.model.ListNovelSeries
import ceui.lisa.model.ListSimpleUser
import ceui.lisa.model.ListTag
import ceui.lisa.model.ListTrendingtag
import ceui.lisa.model.ListUser
import ceui.lisa.model.ListWatchlistManga
import ceui.lisa.model.ListWatchlistNovel
import ceui.lisa.model.RecmdIllust
import ceui.lisa.models.CommentHolder
import ceui.lisa.models.GifResponse
import ceui.lisa.models.IllustSearchResponse
import ceui.lisa.models.NovelSearchResponse
import ceui.lisa.models.NullResponse
import ceui.lisa.models.Preset
import ceui.lisa.models.UserDetailResponse
import ceui.lisa.models.UserFollowDetail
import ceui.lisa.models.UserState
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * app-api.pixiv.net 的 suspend 接口，配合 [Retro.getAppApiSuspend] 使用 —— 与旧 Rx 版
 * `AppApi` 同一个 Retrofit 实例、同一套拦截器/token 逻辑，仅去掉 Rx 适配层。
 *
 * 可空参数传 null 时 Retrofit 自动省略该 Query/Field，等价于 legacy 的多重载。
 * 旧 `AppApi` 里从没被调用过的端点（评论列表、旧版排行、mypixiv 小说等）没有搬过来。
 */
interface AppApiSuspend {

    // ── 排行 / 推荐 / 热门 ────────────────────────────────────────────────

    @GET("v1/illust/ranking?filter=for_android")
    suspend fun getRank(
        @Query("mode") mode: String,
        @Query("date") date: String?,
    ): ListIllust

    @GET("v1/illust/recommended?include_privacy_policy=true&filter=for_android")
    suspend fun getRecmdIllust(
        @Query("include_ranking_illusts") includeRankingIllusts: Boolean,
    ): RecmdIllust

    @GET("v1/trending-tags/{type}?filter=for_android&include_translated_tag_results=true")
    suspend fun getHotTags(@Path("type") type: String): ListTrendingtag

    @GET("v1/illust/new?filter=for_android")
    suspend fun getNewWorks(@Query("content_type") contentType: String): ListIllust

    // ── 搜索 ────────────────────────────────────────────────────────────

    @GET("v1/search/illust?filter=for_android&include_translated_tag_results=true&merge_plain_keyword_results=true")
    suspend fun searchIllust(
        @Query("word") word: String,
        @Query("sort") sort: String?,
        @Query("start_date") startDate: String?,
        @Query("end_date") endDate: String?,
        @Query("search_target") searchTarget: String?,
        @Query("bookmark_num_min") bookmarkNumMin: Int? = null,
        @Query("bookmark_num_max") bookmarkNumMax: Int? = null,
        @Query("tool") tool: String? = null,
        @Query("lang") lang: String? = null,
        @Query("search_ai_type") searchAiType: Int? = null,
        @Query("ratio_pattern") ratioPattern: String? = null,
        @Query("content_type") contentType: String? = null,
        @Query("width_min") widthMin: Int? = null,
        @Query("width_max") widthMax: Int? = null,
        @Query("height_min") heightMin: Int? = null,
        @Query("height_max") heightMax: Int? = null,
    ): ListIllust

    /** 借号搜索：显式 Authorization，[TokenInterceptor] 见 X-Shaft-Explicit-Authorization 不再覆盖。 */
    @Headers("X-Shaft-Explicit-Authorization: 1")
    @GET("v1/search/illust?filter=for_android&include_translated_tag_results=true&merge_plain_keyword_results=true")
    suspend fun searchIllustWithAuth(
        @Header("Authorization") authorization: String,
        @Query("word") word: String,
        @Query("sort") sort: String?,
        @Query("start_date") startDate: String?,
        @Query("end_date") endDate: String?,
        @Query("search_target") searchTarget: String?,
        @Query("bookmark_num_min") bookmarkNumMin: Int? = null,
        @Query("bookmark_num_max") bookmarkNumMax: Int? = null,
        @Query("tool") tool: String? = null,
        @Query("lang") lang: String? = null,
        @Query("search_ai_type") searchAiType: Int? = null,
        @Query("ratio_pattern") ratioPattern: String? = null,
        @Query("content_type") contentType: String? = null,
        @Query("width_min") widthMin: Int? = null,
        @Query("width_max") widthMax: Int? = null,
        @Query("height_min") heightMin: Int? = null,
        @Query("height_max") heightMax: Int? = null,
    ): ListIllust

    @GET("v1/search/novel?filter=for_android&include_translated_tag_results=true&merge_plain_keyword_results=true")
    suspend fun searchNovel(
        @Query("word") word: String,
        @Query("sort") sort: String?,
        @Query("start_date") startDate: String?,
        @Query("end_date") endDate: String?,
        @Query("search_target") searchTarget: String?,
        @Query("bookmark_num_min") bookmarkNumMin: Int? = null,
        @Query("bookmark_num_max") bookmarkNumMax: Int? = null,
        @Query("genre") genre: Int? = null,
        @Query("lang") lang: String? = null,
        @Query("search_ai_type") searchAiType: Int? = null,
        @Query("is_original_only") isOriginalOnly: Boolean? = null,
        @Query("is_replaceable_only") isReplaceableOnly: Boolean? = null,
        @Query("text_length_min") textLengthMin: Int? = null,
        @Query("text_length_max") textLengthMax: Int? = null,
        @Query("word_count_min") wordCountMin: Int? = null,
        @Query("word_count_max") wordCountMax: Int? = null,
        @Query("reading_time_min") readingTimeMin: Int? = null,
        @Query("reading_time_max") readingTimeMax: Int? = null,
    ): ListNovel

    @Headers("X-Shaft-Explicit-Authorization: 1")
    @GET("v1/search/novel?filter=for_android&include_translated_tag_results=true&merge_plain_keyword_results=true")
    suspend fun searchNovelWithAuth(
        @Header("Authorization") authorization: String,
        @Query("word") word: String,
        @Query("sort") sort: String?,
        @Query("start_date") startDate: String?,
        @Query("end_date") endDate: String?,
        @Query("search_target") searchTarget: String?,
        @Query("bookmark_num_min") bookmarkNumMin: Int? = null,
        @Query("bookmark_num_max") bookmarkNumMax: Int? = null,
        @Query("genre") genre: Int? = null,
        @Query("lang") lang: String? = null,
        @Query("search_ai_type") searchAiType: Int? = null,
        @Query("is_original_only") isOriginalOnly: Boolean? = null,
        @Query("is_replaceable_only") isReplaceableOnly: Boolean? = null,
        @Query("text_length_min") textLengthMin: Int? = null,
        @Query("text_length_max") textLengthMax: Int? = null,
        @Query("word_count_min") wordCountMin: Int? = null,
        @Query("word_count_max") wordCountMax: Int? = null,
        @Query("reading_time_min") readingTimeMin: Int? = null,
        @Query("reading_time_max") readingTimeMax: Int? = null,
    ): ListNovel

    @GET("v1/search/popular-preview/illust?filter=for_android&include_translated_tag_results=true&merge_plain_keyword_results=true")
    suspend fun popularPreview(
        @Query("word") word: String,
        @Query("start_date") startDate: String?,
        @Query("end_date") endDate: String?,
        @Query("search_target") searchTarget: String?,
        @Query("bookmark_num_min") bookmarkNumMin: Int? = null,
        @Query("bookmark_num_max") bookmarkNumMax: Int? = null,
        @Query("tool") tool: String? = null,
        @Query("lang") lang: String? = null,
        @Query("search_ai_type") searchAiType: Int? = null,
        @Query("ratio_pattern") ratioPattern: String? = null,
        @Query("content_type") contentType: String? = null,
        @Query("width_min") widthMin: Int? = null,
        @Query("width_max") widthMax: Int? = null,
        @Query("height_min") heightMin: Int? = null,
        @Query("height_max") heightMax: Int? = null,
    ): ListIllust

    @GET("v1/search/popular-preview/novel?filter=for_android&include_translated_tag_results=true&merge_plain_keyword_results=true")
    suspend fun popularNovelPreview(
        @Query("word") word: String,
        @Query("start_date") startDate: String?,
        @Query("end_date") endDate: String?,
        @Query("search_target") searchTarget: String?,
        @Query("bookmark_num_min") bookmarkNumMin: Int? = null,
        @Query("bookmark_num_max") bookmarkNumMax: Int? = null,
        @Query("genre") genre: Int? = null,
        @Query("lang") lang: String? = null,
        @Query("search_ai_type") searchAiType: Int? = null,
        @Query("is_original_only") isOriginalOnly: Boolean? = null,
        @Query("is_replaceable_only") isReplaceableOnly: Boolean? = null,
        @Query("text_length_min") textLengthMin: Int? = null,
        @Query("text_length_max") textLengthMax: Int? = null,
        @Query("word_count_min") wordCountMin: Int? = null,
        @Query("word_count_max") wordCountMax: Int? = null,
        @Query("reading_time_min") readingTimeMin: Int? = null,
        @Query("reading_time_max") readingTimeMax: Int? = null,
    ): ListNovel

    @GET("v1/search/user?filter=for_android")
    suspend fun searchUser(@Query("word") word: String): ListUser

    // ── 作品 / 详情 ─────────────────────────────────────────────────────

    @GET("v1/illust/detail?filter=for_android")
    suspend fun getIllustByID(@Query("illust_id") illustId: Long): IllustSearchResponse

    @GET("v2/novel/detail")
    suspend fun getNovelByID(@Query("novel_id") novelId: Long): NovelSearchResponse

    @GET("v2/illust/related?filter=for_android")
    suspend fun relatedIllust(@Query("illust_id") illustId: Long): ListIllust

    @GET("v1/ugoira/metadata")
    suspend fun getGifPackage(@Query("illust_id") illustId: Long): GifResponse

    /** 小说正文 webview 页；仍是 [Call]，调用方需要原始 body。 */
    @GET("/webview/v2/novel")
    fun getNovelDetailV2(@Query("id") id: Long): Call<ResponseBody>

    @GET("v2/novel/series")
    suspend fun getNovelSeries(@Query("series_id") seriesId: Int): ListNovelOfSeries

    // ── 用户 ────────────────────────────────────────────────────────────

    @GET("v1/user/illusts?filter=for_android")
    suspend fun getUserSubmitIllust(
        @Query("user_id") userId: Int,
        @Query("type") type: String,
    ): ListIllust

    /** tag 传 null 时 Retrofit 自动省略该 Query，等价于 legacy 的双重载。 */
    @GET("v1/user/bookmarks/illust")
    suspend fun getUserLikeIllust(
        @Query("user_id") userId: Int,
        @Query("restrict") restrict: String,
        @Query("tag") tag: String? = null,
    ): ListIllust

    @GET("v2/user/detail?filter=for_ios")
    suspend fun getUserDetailV2(@Query("user_id") userId: Int): UserDetailResponse

    @GET("v1/user/follow/detail")
    suspend fun getFollowDetail(@Query("user_id") userId: Int): UserFollowDetail

    @GET("v1/user/following?filter=for_android")
    suspend fun getFollowUser(
        @Query("user_id") userId: Int,
        @Query("restrict") restrict: String,
        @Query("offset") offset: Int? = null,
    ): ListUser

    @GET("v1/user/related?filter=for_android")
    suspend fun getRelatedUsers(@Query("seed_user_id") seedUserId: Int): ListUser

    @GET("v1/illust/bookmark/users?filter=for_android")
    suspend fun getUsersWhoLikeThisIllust(@Query("illust_id") illustId: Int): ListSimpleUser

    @GET("v1/novel/bookmark/users?filter=for_android")
    suspend fun getUsersWhoLikeThisNovel(@Query("novel_id") novelId: Long): ListSimpleUser

    @GET("v1/user/illust-series")
    suspend fun getUserMangaSeries(@Query("user_id") userId: Int): ListMangaSeries

    @GET("v1/user/novel-series")
    suspend fun getUserNovelSeries(@Query("user_id") userId: Int): ListNovelSeries

    @GET("v1/user/me/state")
    suspend fun getAccountState(): UserState

    @GET("v1/user/profile/presets")
    suspend fun getPresets(): Preset

    @Multipart
    @POST("v1/user/profile/edit")
    suspend fun updateUserProfile(@Part parts: List<MultipartBody.Part>): NullResponse

    @FormUrlEncoded
    @POST("v1/user/workspace/edit")
    suspend fun editWorkSpace(@FieldMap fields: Map<String, String>): NullResponse

    // ── 关注 / 收藏 / 评论 ───────────────────────────────────────────────

    @FormUrlEncoded
    @POST("v1/user/follow/add")
    suspend fun postFollow(
        @Field("user_id") userId: Int,
        @Field("restrict") followType: String,
    ): NullResponse

    @FormUrlEncoded
    @POST("v1/user/follow/delete")
    suspend fun postUnFollow(@Field("user_id") userId: Int): NullResponse

    @FormUrlEncoded
    @POST("v2/illust/bookmark/add")
    suspend fun postLikeIllust(
        @Field("illust_id") illustId: Int,
        @Field("restrict") restrict: String,
    ): NullResponse

    @FormUrlEncoded
    @POST("v1/illust/comment/add")
    suspend fun postIllustComment(
        @Field("illust_id") illustId: Int,
        @Field("comment") comment: String,
        @Field("parent_comment_id") parentCommentId: Int? = null,
    ): CommentHolder

    @FormUrlEncoded
    @POST("v1/novel/comment/add")
    suspend fun postNovelComment(
        @Field("novel_id") novelId: Int,
        @Field("comment") comment: String,
        @Field("parent_comment_id") parentCommentId: Int? = null,
    ): CommentHolder

    // ── 收藏标签 ────────────────────────────────────────────────────────

    @GET("v1/user/bookmark-tags/illust")
    suspend fun getAllIllustBookmarkTags(
        @Query("user_id") userId: Int,
        @Query("restrict") restrict: String,
    ): ListTag

    @GET("v1/user/bookmark-tags/novel")
    suspend fun getAllNovelBookmarkTags(
        @Query("user_id") userId: Int,
        @Query("restrict") restrict: String,
    ): ListTag

    @GET("v2/illust/bookmark/detail")
    suspend fun getIllustBookmarkTags(@Query("illust_id") illustId: Int): ListBookmarkTag

    @GET("v2/novel/bookmark/detail")
    suspend fun getNovelBookmarkTags(@Query("novel_id") novelId: Int): ListBookmarkTag

    // ── 小说书签 / 追更 ──────────────────────────────────────────────────

    @GET("v2/novel/markers")
    suspend fun getNovelMarkers(): ListNovelMarkers

    @FormUrlEncoded
    @POST("v1/novel/marker/add")
    suspend fun postAddNovelMarker(
        @Field("novel_id") novelId: Int,
        @Field("page") page: Int,
    ): NullResponse

    @FormUrlEncoded
    @POST("v1/novel/marker/delete")
    suspend fun postDeleteNovelMarker(@Field("novel_id") novelId: Int): NullResponse

    @GET("v1/watchlist/novel")
    suspend fun getWatchlistNovel(): ListWatchlistNovel

    @FormUrlEncoded
    @POST("v1/watchlist/novel/add")
    suspend fun postWatchlistNovelAdd(@Field("series_id") seriesId: Int): NullResponse

    @FormUrlEncoded
    @POST("v1/watchlist/novel/delete")
    suspend fun postWatchlistNovelDelete(@Field("series_id") seriesId: Int): NullResponse

    @GET("v1/watchlist/manga")
    suspend fun getWatchlistManga(): ListWatchlistManga

    @FormUrlEncoded
    @POST("v1/watchlist/manga/add")
    suspend fun postWatchlistMangaAdd(@Field("series_id") seriesId: Int): NullResponse

    @FormUrlEncoded
    @POST("v1/watchlist/manga/delete")
    suspend fun postWatchlistMangaDelete(@Field("series_id") seriesId: Int): NullResponse

    // ── 翻页（next_url）────────────────────────────────────────────────

    @GET
    suspend fun getNextIllust(@Url nextUrl: String): ListIllust

    @Headers("X-Shaft-Explicit-Authorization: 1")
    @GET
    suspend fun getNextIllustWithAuth(
        @Header("Authorization") authorization: String,
        @Url nextUrl: String,
    ): ListIllust

    @GET
    suspend fun getNextNovel(@Url nextUrl: String): ListNovel

    @Headers("X-Shaft-Explicit-Authorization: 1")
    @GET
    suspend fun getNextNovelWithAuth(
        @Header("Authorization") authorization: String,
        @Url nextUrl: String,
    ): ListNovel

    @GET
    suspend fun getNextTags(@Url nextUrl: String): ListTag

    @GET
    suspend fun getNextSimpleUser(@Url nextUrl: String): ListSimpleUser

    @GET
    suspend fun getNextUserNovelSeries(@Url nextUrl: String): ListNovelSeries

    @GET
    suspend fun getNextUserMangaSeries(@Url nextUrl: String): ListMangaSeries

    @GET
    suspend fun getNextNovelMarkers(@Url nextUrl: String): ListNovelMarkers
}
