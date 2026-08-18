package ceui.pixiv.login

import android.net.Uri
import ceui.lisa.activities.Shaft
import ceui.lisa.http.AppApiProxyInterceptor
import ceui.lisa.http.AppApiTimeouts
import ceui.lisa.http.CronetInterceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit

/**
 * Pixiv OAuth 入口，包了库 [PixivOAuthClient]。
 *
 * 懒单例；Shaft 约定改直连设置要重启，因此不做运行期重建。
 * 注意：`client` 是 `by lazy` 单例，**进程生命周期内先登录过、再开 PxveAPI 代理，
 * 本次会话的 token 自动刷新仍走旧客户端**（开关切换只重建 Retro/Client，
 * 不重建这里）。代理对 token 刷新生效的时间点是「下次启动后」。
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
            is PixivOAuthResult.Failure.ServerRejected -> {
                if (result.httpCode == 400 && result.message.contains("Invalid refresh token")) {
                    throw InvalidRefreshTokenException(result.message)
                }
                throw RuntimeException(
                    "Token refresh failed (HTTP ${result.httpCode}): ${result.message}",
                    result.cause,
                )
            }
            is PixivOAuthResult.Failure -> throw RuntimeException(
                "Token refresh failed: ${result.message}",
                result.cause,
            )
        }
    }

    private fun buildClient(): PixivOAuthClient {
        // OAuth 登录/刷新是 app-api 同一套超时（值=10s，收敛到 AppApiTimeouts）；
        // 开启 PxveAPI 代理时改写后的 oauth 请求也走本 client，同样生效。
        val builder = OkHttpClient.Builder()
            .connectTimeout(AppApiTimeouts.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AppApiTimeouts.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(AppApiTimeouts.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
        // App API 代理（PxveAPI 风格）与直连模式**共存**：代理拦截器挂在
        // CronetInterceptor 之前，只改写 oauth 请求到代理域名；改写后的域名不在
        // Cronet MAP 规则内，走系统解析。二者同时开启互不干扰。
        if (Shaft.sSettings.isUseAppApiProxy) {
            builder.addInterceptor(AppApiProxyInterceptor())
        }
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
