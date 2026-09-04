package ceui.pixiv.safe.auth

import com.google.gson.annotations.SerializedName

public data class AuthSession(
    @SerializedName("uid") public val uid: Long,
    @SerializedName("sessionId") public val sessionId: String,
    @SerializedName("deviceId") public val deviceId: String,
    @SerializedName("accessToken") public val accessToken: String,
    @SerializedName("accessExpiresAt") public val accessExpiresAt: Long,
    @SerializedName("refreshToken") public val refreshToken: String,
    @SerializedName("refreshExpiresAt") public val refreshExpiresAt: Long,
    @SerializedName("generation") public val generation: Long,
) {
    public fun isValidFor(expectedUid: Long): Boolean =
        uid > 0L && uid == expectedUid &&
            sessionId.isNotBlank() && deviceId.isNotBlank() &&
            accessToken.startsWith("ps_at_") && refreshToken.startsWith("ps_rt_") &&
            refreshExpiresAt > System.currentTimeMillis() && generation > 0L
}
