package ceui.loxia

import ceui.lisa.models.NullResponse
import okhttp3.ResponseBody
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface API {

    @FormUrlEncoded
    @POST("/v1/illust/report")
    suspend fun postFlagIllust(
        @Field("illust_id") illust_id: Int,
        @Field("type_of_problem") type_of_problem: String?,
        @Field("message") message: String?
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

    @GET("/v1/illust/recommended?include_ranking_illusts=true&include_privacy_policy=true&filter=for_ios")
    suspend fun getHomeData(): HomeIllustResponse

    @GET("/v1/user/illusts?filter=for_ios")
    suspend fun getUserCreatedIllusts(
        @Query("user_id") user_id: Long,
        @Query("type") type: String,
    ): IllustResponse

    @GET("/v1/user/bookmarks/illust?filter=for_ios&restrict=public")
    suspend fun getUserBookmarkedIllusts(
        @Query("user_id") user_id: Long,
    ): IllustResponse


    @GET("/v2/user/detail?filter=for_ios")
    suspend fun getUserProfile(
        @Query("user_id") user_id: Long,
    ): UserResponse

    @GET("/v1/user/following")
    suspend fun getFollowingUsers(
        @Query("user_id") user_id: Long,
        @Query("restrict") restrict: String,
    ): UserPreviewResponse

    @GET("/v1/illust/ranking?filter=for_ios")
    suspend fun getRankingIllusts(
        @Query("mode") mode: String,
    ): IllustResponse

    @GET("/v3/illust/comments")
    suspend fun getIllustComments(
        @Query("illust_id") illust_id: Long,
    ): CommentResponse

    @GET("/v2/illust/comment/replies")
    suspend fun getIllustReplyComments(
        @Query("comment_id") comment_id: Long,
    ): CommentResponse

    @GET
    suspend fun generalGet(@Url url: String): ResponseBody
}