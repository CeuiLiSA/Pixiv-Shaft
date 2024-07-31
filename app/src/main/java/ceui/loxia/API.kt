package ceui.loxia

import ceui.lisa.models.NullResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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
}