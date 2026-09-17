package ceui.pixiv.safe.auth

import okhttp3.Interceptor
import okhttp3.Response

internal object AuthRoutes {
    fun requiresSession(path: String): Boolean =
        path.contains("/v1/account/") ||
            path.startsWith("/v1/media/") ||
            path.startsWith("/v1/plaza/") ||
            // 推介计划**只**收 Bearer，没有旧 HMAC 的退路：那把密钥打在 APK 里，谁都能
            // 替任何 uid 说话，而这条路的尽头是真的发出去几天 PRO。拿不到 token 时请求
            // 会以 401 失败，页面说一句「稍后重试」——比把奖励记到别人头上好。
            path.startsWith("/v1/referral/") ||
            path.endsWith("/v1/push/ack")
}

/** Adds the current token; all 401 recovery belongs to [TokenAuthenticator]. */
class BearerInterceptor(private val sessions: SessionProvider) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!AuthRoutes.requiresSession(request.url.encodedPath)) {
            return chain.proceed(request)
        }
        val path = request.url.encodedPath
        if (request.header("Authorization") != null) {
            AuthLog.debug("bearer already present path=$path")
            return chain.proceed(request)
        }

        val token = sessions.accessTokenOrBootstrap()
        val authenticated =
            if (token.isNullOrBlank()) {
                AuthLog.warning("bearer unavailable; continuing migration fallback path=$path")
                request
            } else {
                AuthLog.debug("bearer attached path=$path")
                request
                    .newBuilder()
                    .header("Authorization", "Bearer $token")
                    .header("X-Pixshaft-Auth-Version", "2")
                    .build()
            }
        return chain.proceed(authenticated)
    }
}
