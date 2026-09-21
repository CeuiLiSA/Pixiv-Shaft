package ceui.pixiv.network

import ceui.lisa.helper.LanguageHelper
import ceui.pixiv.api.ClientManager
import ceui.pixiv.session.SessionManager
import com.tencent.mmkv.MMKV
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class WebHeaderInterceptor : Interceptor {

    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(
            addHeader(request).build()
        )
    }

    private fun addHeader(request: Request): Request.Builder {
        // 去重后再发：存量里可能是「匿名 PHPSESSID 在前、登录态在后」的重复串，原样发出去
        // 服务端只认前一条，等于白登录。见 SessionManager.normalizeWebCookie。
        val cookies = SessionManager.normalizeWebCookie(prefStore.getString(SessionManager.COOKIE_KEY, ""))
        val explicitCookie = request.header("Cookie")
        val cookie = explicitCookie ?: cookies
        return request.newBuilder()
            .removeHeader("Cookie")
            .addHeader("Host", "www.pixiv.net")
            .addHeader("Referer", "https://www.pixiv.net/")
            .addHeader("User-Agent", ClientManager.WEB_USER_AGENT)
            .addHeader("accept-language", LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage())
            .apply {
                if (!cookie.isNullOrBlank()) addHeader("Cookie", cookie)
            }
    }
}
