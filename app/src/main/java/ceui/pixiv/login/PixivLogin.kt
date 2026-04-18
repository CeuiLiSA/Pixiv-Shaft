package ceui.pixiv.login

import android.net.Uri
import ceui.lisa.activities.Shaft
import ceui.lisa.http.CronetInterceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * Pixiv OAuth 入口，包了库 [PixivOAuthClient]。
 *
 * 懒单例；Shaft 约定改直连设置要重启，因此不做运行期重建。
 */
object PixivLogin {

    private val client: PixivOAuthClient by lazy { buildClient() }

    fun startLoginUrl(): String = client.startLogin()

    fun startSignUrl(): String = client.startProvisionalAccount()

    /**
     * 处理 OAuth 回调 URI，交换 code → token。
     * **同步阻塞 I/O**，在后台线程调用。
     */
    fun handleCallback(uri: Uri): PixivOAuthResult = client.handleCallback(uri)

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

    private fun buildClient(): PixivOAuthClient {
        val builder = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor(OAuthHeaderInterceptor())
        if (Shaft.sSettings.isDirectConnect) {
            builder.addInterceptor(CronetInterceptor(CronetInterceptor.getEngine(Shaft.getContext())))
        }
        return PixivOAuthClient(
            config = PixivOAuthConfig.PIXIV_ANDROID,
            baseClient = builder.build(),
            verifierStore = MmkvVerifierStore(),
        )
    }
}

class InvalidRefreshTokenException(message: String) : RuntimeException(message)
