package ceui.loxia

import ceui.pixiv.session.SessionManager
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber

class TokenFetcherInterceptor : Interceptor {

    private companion object {
        const val TOKEN_ERROR_PEEK_BYTES = 4096L
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        return if (response.code == 400) {
            // 只为下面两个 marker 做 contains，pixiv 的 token 错误响应不到 200 字节；
            // 有上限地 peek，别把任意大小的 400 响应体整个读进内存。
            val gson = response.peekBody(TOKEN_ERROR_PEEK_BYTES).string()
            // 未登录 / 已登出时不尝试刷新 token,直接返回 400,避免 refreshAccessToken→getAccessToken 抛 "account not found" 炸 OkHttp 线程
            if (SessionManager.isLoggedIn &&
                (gson.contains(ClientManager.TOKEN_ERROR_1) || gson.contains(ClientManager.TOKEN_ERROR_2))) {
                val tokenForThisRequest = request.header(ClientManager.HEADER_AUTH)
                    ?.substring(ClientManager.TOKEN_HEAD.length) ?: ""
                Timber.tag("TokenRefresh").d(
                    "[%s] 400 token error on %s %s → asking for refresh",
                    Thread.currentThread().name, request.method, request.url.encodedPath,
                )
                val refreshedAccessToken = SessionManager.refreshAccessToken(tokenForThisRequest)
                if (refreshedAccessToken != null) {
                    Timber.tag("TokenRefresh").d(
                        "[%s] replaying %s %s with refreshed token",
                        Thread.currentThread().name, request.method, request.url.encodedPath,
                    )
                    // 只有确定要重放时才关旧响应；拿不到新 token 时旧响应要原样交回
                    // Retrofit 读 errorBody，提前 close 会让它抛 "closed"。
                    response.close()
                    val newRequest = chain.request()
                        .newBuilder()
                        .header(ClientManager.HEADER_AUTH, ClientManager.TOKEN_HEAD + refreshedAccessToken)
                        .build()
                    chain.proceed(newRequest)
                } else {
                    Timber.tag("TokenRefresh").w(
                        "[%s] no refreshed token for %s %s → returning original 400",
                        Thread.currentThread().name, request.method, request.url.encodedPath,
                    )
                    response
                }
            } else {
                response
            }
        } else {
            response
        }
    }
}