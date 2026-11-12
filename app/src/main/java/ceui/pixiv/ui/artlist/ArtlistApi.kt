package ceui.pixiv.ui.artlist

import androidx.annotation.Keep
import ceui.lisa.model.ListIllust
import ceui.pixiv.api.model.Illust
import retrofit2.Response
import retrofit2.http.*

@Keep
data class Artlist(
    val id: Long,
    val uid: Long,
    val name: String,
    val description: String,
    val ownerName: String,
    val workCount: Int,
    val saveCount: Int,
    val saved: Boolean,
    val cover: Illust?,
    val containsWork: Boolean = false,
)

@Keep data class ArtlistPage(val items: List<Artlist>, val nextBefore: Long?)
@Keep data class ArtlistCreate(val name: String, val description: String, val ownerName: String, val requestId: String)
@Keep data class ArtlistEdit(val name: String? = null, val description: String? = null, val coverId: Long? = null)
@Keep data class ArtlistAdd(val illust: Illust)

/** Artwork pages deliberately use the same ListIllust/next_url contract as existing feeds. */
interface ArtlistApi {
    @GET("v1/artlists")
    suspend fun lists(@Query("scope") scope: String, @Query("before") before: Long?,
        @Query("r18") r18: Int, @Query("contains") contains: Long?): Response<ArtlistPage>

    @GET("v1/artlists/{id}")
    suspend fun detail(@Path("id") id: Long, @Query("r18") r18: Int): Response<Artlist>

    @GET("v1/artlists/{id}/works")
    suspend fun works(@Path("id") id: Long, @Query("r18") r18: Int): Response<ListIllust>

    @GET
    suspend fun nextWorks(@Url url: String): Response<ListIllust>

    @POST("v1/artlists")
    suspend fun create(@Query("uid") uid: Long, @Body body: ArtlistCreate): Response<Artlist>

    @PATCH("v1/artlists/{id}")
    suspend fun edit(@Path("id") id: Long, @Query("uid") uid: Long, @Query("r18") r18: Int,
        @Body body: ArtlistEdit): Response<Artlist>

    @DELETE("v1/artlists/{id}")
    suspend fun delete(@Path("id") id: Long, @Query("uid") uid: Long): Response<Unit>

    @POST("v1/artlists/{id}/works")
    suspend fun add(@Path("id") id: Long, @Query("uid") uid: Long, @Query("r18") r18: Int,
        @Body body: ArtlistAdd): Response<Artlist>

    @DELETE("v1/artlists/{id}/works/{workId}")
    suspend fun remove(@Path("id") id: Long, @Path("workId") workId: Long,
        @Query("uid") uid: Long, @Query("r18") r18: Int): Response<Artlist>

    @PUT("v1/artlists/{id}/save")
    suspend fun save(@Path("id") id: Long, @Query("uid") uid: Long, @Query("r18") r18: Int): Response<Artlist>

    @DELETE("v1/artlists/{id}/save")
    suspend fun unsave(@Path("id") id: Long, @Query("uid") uid: Long, @Query("r18") r18: Int): Response<Artlist>
}
