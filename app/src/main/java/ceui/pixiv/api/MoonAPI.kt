package ceui.pixiv.api

import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

interface MoonAPI {

    data class MoonSettings(
        val uid: Long,
        val version: Int,
        val updatedAt: Long,
        val payload: JsonObject,
    )

    data class MoonUploadAck(
        val uid: Long,
        val version: Int,
        val updatedAt: Long,
    )

    @GET("v1/settings/{uid}")
    suspend fun getSettings(@Path("uid") uid: Long): MoonSettings

    @PUT("v1/settings/{uid}")
    suspend fun putSettings(
        @Path("uid") uid: Long,
        @Body payload: JsonObject,
    ): MoonUploadAck
}
