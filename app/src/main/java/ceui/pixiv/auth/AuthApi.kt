package ceui.pixiv.auth

import ceui.pixiv.api.ClientManager
import ceui.pixiv.safe.auth.AuthSession
import ceui.pixiv.shaftapi.ShaftHmac
import com.google.gson.annotations.SerializedName
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okio.Buffer
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

internal interface AuthApi {

    @POST("v1/auth/session")
    fun createSession(@Body body: CreateSessionRequest): Call<TokenResponse>

    @POST("v1/auth/token")
    fun refreshSession(
        @Header("Idempotency-Key") attemptId: String,
        @Body body: RefreshSessionRequest,
    ): Call<TokenResponse>

    @POST("v1/auth/logout")
    fun logout(@Body body: LogoutRequest): Call<Unit>
}

internal data class CreateSessionRequest(
    @SerializedName("grant_type") val grantType: String = "app_hmac",
    @SerializedName("uid") val uid: Long,
    @SerializedName("device_id") val deviceId: String,
)

internal data class RefreshSessionRequest(
    @SerializedName("grant_type") val grantType: String = "refresh_token",
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("device_id") val deviceId: String,
)

internal data class LogoutRequest(
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("device_id") val deviceId: String,
)

internal data class TokenResponse(
    @SerializedName("token_type") val tokenType: String = "",
    @SerializedName("access_token") val accessToken: String = "",
    @SerializedName("access_expires_at") val accessExpiresAt: Long = 0L,
    @SerializedName("refresh_token") val refreshToken: String = "",
    @SerializedName("refresh_expires_at") val refreshExpiresAt: Long = 0L,
    @SerializedName("session_id") val sessionId: String = "",
    @SerializedName("uid") val uid: Long = 0L,
    @SerializedName("generation") val generation: Long = 0L,
)

internal fun TokenResponse.toSession(
    expectedUid: Long,
    expectedDeviceId: String,
): AuthSession? {
    if (tokenType != "Bearer" || uid != expectedUid || uid <= 0L) return null
    if (sessionId.isBlank() || expectedDeviceId.isBlank()) return null
    if (!accessToken.startsWith("ps_at_") || !refreshToken.startsWith("ps_rt_")) return null
    if (accessExpiresAt <= 0L || refreshExpiresAt <= accessExpiresAt || generation <= 0L) return null
    return AuthSession(
        uid = uid,
        sessionId = sessionId,
        deviceId = expectedDeviceId,
        accessToken = accessToken,
        accessExpiresAt = accessExpiresAt,
        refreshToken = refreshToken,
        refreshExpiresAt = refreshExpiresAt,
        generation = generation,
    )
}

/**
 * Naked auth client. It must never inherit the main client's authenticator: a 401 from
 * the token endpoint cannot be allowed to recursively refresh itself.
 */
internal object AuthNetwork {
    val api: AuthApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .authenticator(Authenticator.NONE)
            // Bootstrap is authenticated with the existing native app secret.
            // Pixiv OAuth credentials never enter this first-party client.
            .addInterceptor { chain ->
                val request = chain.request()
                val body = request.body
                if (!request.url.encodedPath.endsWith("/v1/auth/session") ||
                    body == null || !ShaftHmac.isConfigured
                ) {
                    chain.proceed(request)
                } else {
                    val raw = Buffer().also { body.writeTo(it) }.readUtf8()
                    chain.proceed(
                        request.newBuilder()
                            .header("X-Shaft-Sign", ShaftHmac.signHex(raw))
                            .build(),
                    )
                }
            }
            .build()

        Retrofit.Builder()
            .baseUrl(ClientManager.PIXSHAFT_API_HOST)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(AuthApi::class.java)
    }
}
