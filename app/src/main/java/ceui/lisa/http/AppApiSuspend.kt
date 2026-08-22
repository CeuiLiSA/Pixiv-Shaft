package ceui.lisa.http

import ceui.lisa.model.ListIllust
import ceui.lisa.model.ListUser
import ceui.lisa.model.RecmdIllust
import ceui.lisa.models.GifResponse
import ceui.lisa.models.IllustSearchResponse
import ceui.lisa.models.NullResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * [AppApi] 的 suspend 版，配合 [Retro.getAppApiSuspend] 使用 —— 同一个 Retrofit 实例、
 * 同一套拦截器/token 逻辑，仅去掉 Rx 适配层。
 *
 * 新代码（ceui.pixiv / ceui.loxia）一律用这里，不要再对 [AppApi] 的 Observable 做
 * blockingFirst / awaitFirst 桥接；legacy 页面还需要的端点留在 [AppApi]，
 * 迁移一个页面就把它用到的端点挪一个过来。
 */
interface AppApiSuspend {

    @GET("v1/illust/ranking?filter=for_android")
    suspend fun getRank(
        @Query("mode") mode: String,
        @Query("date") date: String?,
    ): ListIllust

    @GET("v1/illust/recommended?include_privacy_policy=true&filter=for_android")
    suspend fun getRecmdIllust(
        @Query("include_ranking_illusts") includeRankingIllusts: Boolean,
    ): RecmdIllust

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

    @GET
    suspend fun getNextIllust(@Url nextUrl: String): ListIllust

    @GET("v1/user/following?filter=for_android")
    suspend fun getFollowUser(
        @Query("user_id") userId: Int,
        @Query("restrict") restrict: String,
    ): ListUser

    @GET("v1/ugoira/metadata")
    suspend fun getGifPackage(@Query("illust_id") illustId: Long): GifResponse

    @GET("v1/illust/detail?filter=for_android")
    suspend fun getIllustByID(@Query("illust_id") illustId: Long): IllustSearchResponse

    @FormUrlEncoded
    @POST("v2/illust/bookmark/add")
    suspend fun postLikeIllust(
        @Field("illust_id") illustId: Int,
        @Field("restrict") restrict: String,
    ): NullResponse

    @FormUrlEncoded
    @POST("v1/watchlist/novel/add")
    suspend fun postWatchlistNovelAdd(@Field("series_id") seriesId: Int): NullResponse

    @FormUrlEncoded
    @POST("v1/watchlist/novel/delete")
    suspend fun postWatchlistNovelDelete(@Field("series_id") seriesId: Int): NullResponse

    @FormUrlEncoded
    @POST("v1/watchlist/manga/add")
    suspend fun postWatchlistMangaAdd(@Field("series_id") seriesId: Int): NullResponse

    @FormUrlEncoded
    @POST("v1/watchlist/manga/delete")
    suspend fun postWatchlistMangaDelete(@Field("series_id") seriesId: Int): NullResponse
}
