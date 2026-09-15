package ceui.pixiv.plaza

import retrofit2.http.*

data class PlazaImage(
    val mediaId: String,
    val width: Int,
    val height: Int,
    val contentType: String,
    val url: String,
    val expiresAt: Long,
)

data class PlazaReaction(val emoji: String, val count: Int, val selected: Boolean)

data class PlazaCommentPreview(
    val id: Long,
    val uid: Long,
    val displayName: String,
    val text: String,
    val avatarUrl: String? = null,
    val createdAt: Long = 0,
)

data class PlazaPost(
    val id: Long,
    val uid: Long,
    val displayName: String,
    val text: String,
    val createdAt: Long,
    val objectId: Long?,
    val objectType: String?,
    val replyTo: Long?,
    val likeCount: Int,
    val replyCount: Int,
    val liked: Boolean,
    val images: List<PlazaImage>,
    val title: String = "",
    val reactions: List<PlazaReaction> = emptyList(),
    val commentsPreview: List<PlazaCommentPreview> = emptyList(),
    val avatarUrl: String? = null,
)

data class PlazaPage(val items: List<PlazaPost>, val nextBefore: Long?)

data class CreatePost(
    val requestId: String,
    val text: String,
    val displayName: String,
    val mediaIds: List<String>,
    val objectId: Long? = null,
    val objectType: String? = null,
    val replyTo: Long? = null,
    val title: String = "",
    val avatarUrl: String? = null,
)

data class DeletePost(val ok: Boolean)

interface PlazaApi {
    @GET("v1/plaza/posts")
    suspend fun feed(
        @Query("before") before: Long? = null,
        @Query("limit") limit: Int = 20,
        @Query("author") author: Long? = null,
        @Query("replyTo") replyTo: Long? = null,
    ): PlazaPage

    @GET("v1/plaza/posts/{id}") suspend fun post(@Path("id") id: Long): PlazaPost

    @POST("v1/plaza/posts") suspend fun create(@Body request: CreatePost): PlazaPost

    @PUT("v1/plaza/posts/{id}/like") suspend fun like(@Path("id") id: Long): PlazaPost

    @DELETE("v1/plaza/posts/{id}/like") suspend fun unlike(@Path("id") id: Long): PlazaPost

    @PUT("v1/plaza/posts/{id}/reactions/{emoji}")
    suspend fun react(@Path("id") id: Long, @Path("emoji") emoji: String): PlazaPost

    @DELETE("v1/plaza/posts/{id}/reactions/{emoji}")
    suspend fun unreact(@Path("id") id: Long, @Path("emoji") emoji: String): PlazaPost

    @DELETE("v1/plaza/posts/{id}") suspend fun delete(@Path("id") id: Long): DeletePost
}
