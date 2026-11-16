package ceui.pixiv.network

import ceui.lisa.helper.LanguageHelper
import ceui.pixiv.api.ClientManager
import ceui.pixiv.session.SessionManager
import ceui.pixiv.session.WebSessionManager
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.tencent.mmkv.MMKV
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class WebHeaderInterceptor : Interceptor {

    private val prefStore: MMKV by lazy {
        MMKV.defaultMMKV()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = addHeader(chain.request()).build()
        val uid = SessionManager.loggedInUid
        val cookie = request.header("Cookie").orEmpty()
        val response = chain.proceed(request)
        if (response.isSuccessful && WebSessionManager.isCurrentSession(uid, cookie) && isWebSuccess(response)) {
            WebSessionManager.recordSuccess(uid, cookie)
        }
        return response
    }

    /** HTTP 200 也可能装着 error=true；只窥读响应前缀，不消费正文或缓冲整份作品列表。 */
    private fun isWebSuccess(response: Response): Boolean = runCatching {
        JsonReader(response.peekBody(1024L).charStream()).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "error") {
                    return@runCatching reader.peek() == JsonToken.BOOLEAN && !reader.nextBoolean()
                }
                reader.skipValue()
            }
            false
        }
    }.getOrDefault(false)

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
