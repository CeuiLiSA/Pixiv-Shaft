package ceui.loxia

import ceui.lisa.helper.LanguageHelper
import ceui.pixiv.session.SessionManager
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class HeaderInterceptor : Interceptor {

    companion object {
        // 对齐 Pixiv iOS 官方客户端抓包（8.6.10 / iOS 26.5 / iPhone16,2），改版本号只改这里
        const val APP_VERSION = "8.6.10"
        const val APP_OS_VERSION = "26.5"
        const val DEVICE_MODEL = "iPhone16,2"
        const val USER_AGENT = "PixivIOSApp/$APP_VERSION (iOS $APP_OS_VERSION; $DEVICE_MODEL)"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(
            addHeader(
                chain.request().newBuilder()
            ).build()
        )
    }

    private fun addHeader(before: Request.Builder): Request.Builder {
        val requestNonce = RequestNonce.build()
        // 未登录 / 已登出时不带 Authorization,让服务端返回 401 由上层处理,避免拦截器抛 RuntimeException 炸 OkHttp 线程。
        // 只读一次：先判 isLoggedIn 再取 token 的话,两次读之间被登出就又会抛 RuntimeException。
        val bearerToken = SessionManager.getBearerTokenOrEmpty()
        if (bearerToken.isNotEmpty()) {
            before.addHeader(ClientManager.HEADER_AUTH, bearerToken)
        }
        before.addHeader("accept-language", LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage())
            .addHeader("app-accept-language", LanguageHelper.getRequestHeaderAppAcceptLanguageFromAppLanguage())
            .addHeader("app-os", "ios")
            .addHeader("app-os-version", APP_OS_VERSION)
            .addHeader("app-version", APP_VERSION)
            .addHeader("x-client-time", requestNonce.xClientTime)
            .addHeader("x-client-hash", requestNonce.xClientHash)
        before.addHeader("user-agent", USER_AGENT)
        return before
    }
}
