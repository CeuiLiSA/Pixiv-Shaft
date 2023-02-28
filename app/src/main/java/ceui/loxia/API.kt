package ceui.loxia

import ceui.lisa.models.NullResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

interface API {

    @FormUrlEncoded
    @POST("/v1/illust/report")
    suspend fun postFlagIllust(
        @Field("illust_id") illust_id: Int,
        @Field("type_of_problem") type_of_problem: String?,
        @Field("message") message: String?
    ): NullResponse
}