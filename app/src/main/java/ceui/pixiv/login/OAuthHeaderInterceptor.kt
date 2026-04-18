package ceui.pixiv.login

import ceui.lisa.helper.LanguageHelper
import ceui.loxia.RequestNonce
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OAuth 端点专用 header 拦截器：只加 Pixiv 必需的客户端头，不加 `Authorization`。
 *
 * 与应用 API 共用的 [ceui.loxia.HeaderInterceptor] 的区别：不携带 access_token。
 * 原因见库 [PixivOAuthClient] 注释——OAuth 客户端加 bearer 拦截器会产生鸡生蛋循环，
 * 且 `SessionManager.getAccessToken()` 在未登录时会抛异常。
 */
internal class OAuthHeaderInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val nonce = RequestNonce.build()
        val request = chain.request().newBuilder()
            .addHeader("accept-language", LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage())
            .addHeader("app-os", "ios")
            .addHeader("app-version", "7.13.4")
            .addHeader("x-client-time", nonce.xClientTime)
            .addHeader("x-client-hash", nonce.xClientHash)
            .addHeader("user-agent", "PixivIOSApp/7.13.4 (iOS 16.0.3; iPhone13,3)")
            .build()
        return chain.proceed(request)
    }
}
