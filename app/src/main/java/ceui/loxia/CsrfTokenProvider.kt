package ceui.loxia

import ceui.pixiv.session.SessionManager
import com.tencent.mmkv.MMKV
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Fetches and caches the x-csrf-token required by Pixiv web POST APIs.
 * The token is extracted from the `<meta id="meta-global-data">` tag
 * on the Pixiv homepage.
 */
object CsrfTokenProvider {

    private const val KEY_CSRF = "web-api-csrf-token"
    private val store: MMKV by lazy { MMKV.defaultMMKV() }

    @Volatile
    private var cached: String? = null

    fun get(): String? = cached ?: store.decodeString(KEY_CSRF, null)?.also { cached = it }

    /**
     * Fetch a fresh token from the Pixiv homepage. Call from a background thread.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().followRedirects(true).build()
    }

    fun fetch(): String? {
        return try {
            val cookies = store.decodeString(SessionManager.COOKIE_KEY, "") ?: ""
            Timber.d("CsrfToken: cookie length=${cookies.length}, empty=${cookies.isEmpty()}")
            if (cookies.isEmpty()) {
                Timber.w("CsrfToken: no web cookie stored, cannot fetch token")
                return null
            }
            val request = Request.Builder()
                .url("https://www.pixiv.net/")
                .addHeader("Cookie", cookies)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.42 Mobile Safari/537.36")
                .build()
            val response = client.newCall(request).execute()
            Timber.d("CsrfToken: HTTP ${response.code}, url=${response.request.url}")
            val body = response.use { it.body?.string() }
            if (body == null) {
                Timber.w("CsrfToken: response body is null")
                return null
            }
            Timber.d("CsrfToken: body length=${body.length}, has meta-global-data=${body.contains("meta-global-data")}")
            val token = parseToken(body)
            if (token != null) {
                Timber.d("CsrfToken: parsed token=${token.take(8)}...")
                cached = token
                store.encode(KEY_CSRF, token)
            } else {
                // 打印 HTML 片段帮助调试
                val snippet = body.take(2000)
                Timber.w("CsrfToken: failed to parse token from HTML, snippet:\n$snippet")
            }
            token
        } catch (e: Exception) {
            Timber.e(e, "CsrfToken: fetch exception")
            null
        }
    }

    private fun parseToken(html: String): String? {
        val tokenRegex = Regex(""""token"\s*:\s*"([a-f0-9]{32})"""")

        // Pattern 1: legacy <meta id="meta-global-data" content='...'>
        val metaRegex = Regex("""id="meta-global-data"\s+content='([^']+)'""")
        metaRegex.find(html)?.let { match ->
            tokenRegex.find(match.groupValues[1])?.let { return it.groupValues[1] }
        }

        // Pattern 2: Next.js __NEXT_DATA__ → serverSerializedPreloadedState 内含 "token":"xxx"
        val nextDataRegex = Regex("""__NEXT_DATA__[^>]*>(.*?)</script>""")
        nextDataRegex.find(html)?.let { match ->
            // serverSerializedPreloadedState 是转义后的 JSON 字符串，直接在里面找 token
            tokenRegex.find(match.groupValues[1])?.let { return it.groupValues[1] }
        }

        // Pattern 3: 兜底，全文搜索 "token":"32位hex"
        tokenRegex.find(html)?.let { return it.groupValues[1] }

        Timber.w("CsrfToken: no token found in HTML (length=${html.length})")
        return null
    }

    fun clear() {
        cached = null
        store.removeValueForKey(KEY_CSRF)
    }
}
