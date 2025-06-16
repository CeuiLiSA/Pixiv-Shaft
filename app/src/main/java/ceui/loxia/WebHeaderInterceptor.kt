package ceui.loxia

import ceui.lisa.helper.LanguageHelper
import ceui.lisa.utils.Common
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.settings.SettingsFragment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class WebHeaderInterceptor : Interceptor {

    private val prefStore = MMKV.mmkvWithID("shaft-session")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(
            addHeader(
                request.newBuilder()
            ).build()
        )
    }

    private fun addHeader(before: Request.Builder): Request.Builder {
//        val end2 = end.replace("first_visit_datetime", "first_visit_datetime_pc")
        before.addHeader("accept-language", LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage())
            .addHeader("Host", "www.pixiv.net")
            .addHeader("Referer", "https://www.pixiv.net/")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6 Build/UQ1A.240105.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/123.0.6312.42 Mobile Safari/537.36 AgentWeb/4.1.9  UCBrowser/11.6.4.950")

        val cookies = prefStore.getString(SessionManager.COOKIE_KEY, "") ?: ""
        if (cookies.isNotEmpty()) {
            before.addHeader("Cookie", cookies)
        }

        return before
    }
}