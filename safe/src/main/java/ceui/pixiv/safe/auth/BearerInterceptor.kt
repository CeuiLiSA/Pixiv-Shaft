package ceui.pixiv.safe.auth

import okhttp3.Interceptor
import okhttp3.Response

internal object AuthRoutes {
    fun requiresSession(path: String): Boolean =
        path.contains("/v1/account/") || path.endsWith("/v1/push/ack")
}

/** Adds the current token; all 401 recovery belongs to [TokenAuthenticator]. */
class BearerInterceptor(
    private val sessions: SessionProvider,
) : Interceptor {

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
        val authenticated = if (token.isNullOrBlank()) {
            AuthLog.warning("bearer unavailable; continuing migration fallback path=$path")
            request
        } else {
            AuthLog.debug("bearer attached path=${path}, token=${token}")
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .header("X-Pixshaft-Auth-Version", "2")
                .build()
        }
        return chain.proceed(authenticated)
    }
}
