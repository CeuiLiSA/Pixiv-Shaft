package ceui.pixiv.shaftapi

/**
 * Everything a retry needs to finish an upload without repeating the PUT: the pending media ID
 * and its still-valid signed upload authorisation. Persisted by the caller (SavedStateHandle) as
 * soon as init succeeds, and discarded once the upload is complete.
 */
data class MediaUploadResume(
    val mediaId: String,
    val objectKey: String,
    val uploadUrl: String,
    val headers: Map<String, String>,
    val expiresAt: Long,
    val contentType: String,
    val size: Long,
)
