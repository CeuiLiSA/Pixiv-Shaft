package ceui.loxia

import ceui.lisa.helper.LanguageHelper
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
            addHeader(
                request.newBuilder()
            ).build()
        )
    }

    private fun addHeader(before: Request.Builder): Request.Builder {
        // 去重后再发：存量里可能是「匿名 PHPSESSID 在前、登录态在后」的重复串，原样发出去
        // 服务端只认前一条，等于白登录。见 SessionManager.normalizeWebCookie。
        val cookies = SessionManager.normalizeWebCookie(prefStore.getString(SessionManager.COOKIE_KEY, ""))
        before.addHeader("accept-language", LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage())
            .addHeader("Host", "www.pixiv.net")
            .addHeader("Cookie", cookies)
            .addHeader("Referer", "https://www.pixiv.net/")
            .addHeader("User-Agent", ClientManager.WEB_USER_AGENT)
        return before
    }
}
