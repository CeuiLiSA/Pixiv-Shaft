package ceui.pixiv.safe.auth

import com.google.gson.annotations.SerializedName

data class AuthSession(
    @SerializedName("uid") val uid: Long,
    @SerializedName("sessionId") val sessionId: String,
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("accessToken") val accessToken: String,
    @SerializedName("accessExpiresAt") val accessExpiresAt: Long,
    @SerializedName("refreshToken") val refreshToken: String,
    @SerializedName("refreshExpiresAt") val refreshExpiresAt: Long,
    @SerializedName("generation") val generation: Long,
) {
    fun isValidFor(expectedUid: Long): Boolean =
        uid > 0L && uid == expectedUid &&
            sessionId.isNotBlank() && deviceId.isNotBlank() &&
            accessToken.startsWith("ps_at_") && refreshToken.startsWith("ps_rt_") &&
            refreshExpiresAt > System.currentTimeMillis() && generation > 0L
}
