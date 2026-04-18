package ceui.pixiv.login

import android.net.Uri
import ceui.lisa.activities.Shaft
import ceui.lisa.http.CronetInterceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Pixiv OAuth 入口，包了库 [PixivOAuthClient]：
 * - PKCE verifier 持久化在 MMKV，抗进程被杀。
 * - OkHttp 共用 [OAuthHeaderInterceptor]（app-os / x-client-hash 等 Pixiv 必需头，不含 Authorization），
 *   DirectConnect 模式下接入 Cronet。
 *
 * 懒单例；Shaft 约定改直连设置要重启，因此不做运行期重建。
 */
object PixivLogin {

    const val GRANT_TYPE_AUTH_CODE = "authorization_code"
    const val GRANT_TYPE_REFRESH_TOKEN = "refresh_token"

    private const val LOGIN_URL = "https://app-api.pixiv.net/web/v1/login"
    private const val SIGN_URL = "https://app-api.pixiv.net/web/v1/provisional-accounts/create"
    private const val TIMEOUT_SECONDS = 10L

    private val verifierStore = MmkvVerifierStore()

    private val client: PixivOAuthClient by lazy { buildClient() }

    fun startLoginUrl(): String = buildPkceUrl(LOGIN_URL)

    fun startSignUrl(): String = buildPkceUrl(SIGN_URL)

    fun loadVerifier(): String? = verifierStore.load()

    fun clearVerifier() {
        verifierStore.clear()
    }

    /**
     * 同步刷新 token。在后台线程调用。
     * - 成功返回 [PixivOAuthResponse]。
     * - refresh_token 被吊销时抛 [InvalidRefreshTokenException]，调用方应触发登出。
     * - 其它失败抛 [RuntimeException]。
     */
    fun refreshTokenBlocking(refreshToken: String): PixivOAuthResponse {
        return when (val result = client.refreshToken(refreshToken)) {
            is PixivOAuthResult.Success -> result.response
            is PixivOAuthResult.Failure -> {
                if (result.httpCode == 400 && result.message.contains("Invalid refresh token")) {
                    throw InvalidRefreshTokenException(result.message)
                }
                throw RuntimeException(
                    "Token refresh failed (http=${result.httpCode}): ${result.message}",
                    result.cause,
                )
            }
        }
    }

    private fun buildPkceUrl(base: String): String {
        val pkce = PkceUtil.generate()
        verifierStore.save(pkce.verifier)
        return Uri.parse(base)
            .buildUpon()
            .appendQueryParameter("code_challenge", pkce.challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("client", PixivOAuthConfig.PIXIV_ANDROID.clientParam)
            .build()
            .toString()
    }

    private fun buildClient(): PixivOAuthClient {
        val builder = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(OAuthHeaderInterceptor())
            .addInterceptor(
                // BASIC：只记 method / url / status / latency，不打 token body。
                HttpLoggingInterceptor { message -> Timber.i(message) }
                    .apply { level = HttpLoggingInterceptor.Level.BASIC },
            )
        if (Shaft.sSettings.isDirectConnect) {
            builder.addInterceptor(CronetInterceptor(CronetInterceptor.getEngine(Shaft.getContext())))
        }
        return PixivOAuthClient(
            config = PixivOAuthConfig.PIXIV_ANDROID,
            baseClient = builder.build(),
            verifierStore = verifierStore,
        )
    }
}

class InvalidRefreshTokenException(message: String) : RuntimeException(message)
