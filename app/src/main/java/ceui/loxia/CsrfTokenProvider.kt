package ceui.loxia

import ceui.lisa.activities.Shaft
import ceui.lisa.http.CronetInterceptor
import ceui.lisa.http.IPv4OnlyDns
import ceui.lisa.http.WebApiTimeouts
import ceui.pixiv.session.SessionManager
import com.tencent.mmkv.MMKV
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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

    fun set(token: String) {
        cached = token
        store.encode(KEY_CSRF, token)
    }

    /** 缓存优先，没有就现抓。[fetch] 是阻塞 I/O，**必须在后台线程调用**。 */
    fun getOrFetch(): String? = get() ?: fetch()

    /**
     * Fetch a fresh token from the Pixiv homepage. Call from a background thread.
     */
    private fun buildClient(): OkHttpClient {
        // 打的是 www.pixiv.net，和 [ClientManager.createWebAPIService] 同一条链路，
        // 超时与 IPv4-only DNS 也跟着它走：不然非直连下被污染的 IPv6 会让这次
        // **阻塞式**兜底抓取先干等一轮，把调用它的网页 POST 一起拖住。
        val builder = OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(WebApiTimeouts.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(WebApiTimeouts.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WebApiTimeouts.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .dns(IPv4OnlyDns)
        // issue #959: 直连下 www.pixiv.net 同样打不通,token 兜底抓取必须走 Cronet,
        // 否则「拉黑」在没梯子时永远卡在「CSRF token 未就绪」。每次现建:直连开关随时可切。
        if (Shaft.sSettings?.isDirectConnect == true) {
            builder.addInterceptor(CronetInterceptor(CronetInterceptor.getEngine(Shaft.getContext())))
        }
        return builder.build()
    }

    fun fetch(): String? {
        return try {
            val cookies = SessionManager.normalizeWebCookie(store.decodeString(SessionManager.COOKIE_KEY, ""))
            if (cookies.isEmpty()) {
                return null
            }
            val request = Request.Builder()
                .url("https://www.pixiv.net/")
                .addHeader("Cookie", cookies)
                .addHeader("User-Agent", ClientManager.WEB_USER_AGENT)
                .build()
            val response = buildClient().newCall(request).execute()
            val body = response.use { it.body?.string() }
            if (body == null) {
                return null
            }
            val token = parseToken(body)
            if (token != null) {
                cached = token
                store.encode(KEY_CSRF, token)
            }
            token
        } catch (_: Exception) {
            null
        }
    }

    private fun parseToken(html: String): String? {
        // 引号写成可选转义：pixiv 改版到 Next.js 后 token 埋在 __NEXT_DATA__ 的**嵌套 JSON
        // 字符串**里，原文是 \"token\":\"<32hex>\"。按裸引号匹配三条 pattern 会一起落空，
        // 这条 OkHttp 兜底链路(拉黑用的就是它)于是恒返 null。与 StreetMainFragment 的
        // EXTRACT_TOKEN_JS 用同一形态。
        val tokenRegex = Regex("""token\\?"\s*:\s*\\?"([a-f0-9]{32})""")

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

        return null
    }

    fun clear() {
        cached = null
        store.removeValueForKey(KEY_CSRF)
    }
}
