package ceui.pixiv.shaftapi

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Media metadata and upload-authorisation API.
 *
 * The app never receives a COS permanent credential. The API returns a
 * short-lived upload URL (or SDK session in a future version), and the bytes
 * go directly from the device to COS. The Tokyo API stores only metadata.
 */
interface MediaApi {

    @POST("v1/media/upload/init")
    suspend fun initUpload(@Body request: MediaUploadInitRequest): MediaUploadInitResponse

    @POST("v1/media/upload/complete")
    suspend fun completeUpload(@Body request: MediaUploadCompleteRequest): MediaObject

    @GET("v1/media/{mediaId}/download-url")
    suspend fun downloadUrl(@Path("mediaId") mediaId: String): MediaDownloadUrlResponse
}

data class MediaUploadInitRequest(
    val scene: String,
    val contentType: String,
    val size: Long,
    val sha256: String? = null,
    val fileName: String? = null,
)

data class MediaUploadInitResponse(
    val mediaId: String,
    val objectKey: String,
    val method: String,
    val uploadUrl: String,
    val expiresAt: Long,
    val headers: Map<String, String> = emptyMap(),
)

data class MediaUploadCompleteRequest(
    val mediaId: String,
    val objectKey: String,
    val contentType: String,
    val size: Long,
    val etag: String? = null,
    val sha256: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

data class MediaObject(
    @SerializedName("mediaId") val id: String,
    val objectKey: String,
    val contentType: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    val createdAt: String,
)

data class MediaDownloadUrlResponse(
    val mediaId: String,
    val url: String,
    val expiresAt: Long,
)
