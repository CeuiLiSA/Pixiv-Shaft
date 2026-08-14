package ceui.loxia

import ceui.lisa.activities.Shaft
import ceui.lisa.http.CronetInterceptor
import ceui.lisa.http.NetTimeouts
import ceui.pixiv.session.SessionManager
import com.tencent.mmkv.MMKV
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
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
        val builder = OkHttpClient.Builder()
            .followRedirects(true)
            // 连接统一 3s；读超时放宽到 BODY_READ_SECONDS：抓的是 www.pixiv.net 整页 HTML
            //（可达数百 KB），慢网下 3s 读超时会误杀 CSRF 抓取，而失败只是暂无 token、下次再抓。
            .connectTimeout(NetTimeouts.CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetTimeouts.BODY_READ_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(NetTimeouts.API_WRITE_SECONDS, TimeUnit.SECONDS)
        // issue #959: 直连下 www.pixiv.net 同样打不通,token 兜底抓取必须走 Cronet,
        // 否则「拉黑」在没梯子时永远卡在「CSRF token 未就绪」。每次现建:直连开关随时可切。
        if (Shaft.sSettings?.isDirectConnect == true) {
            // Cronet 请求不走 OkHttp 分阶段超时，拦截器整体上限显式放宽到 BODY_READ_SECONDS，
            // 与上面的 readTimeout 同值：直连模式下整页 HTML 抓取不被默认 3s 截断。
            builder.addInterceptor(
                CronetInterceptor(
                    CronetInterceptor.getEngine(Shaft.getContext()),
                    NetTimeouts.BODY_READ_SECONDS,
                ),
            )
        }
        return builder.build()
    }

    fun fetch(): String? {
        return try {
            val cookies = SessionManager.normalizeWebCookie(store.decodeString(SessionManager.COOKIE_KEY, ""))
            Timber.d("CsrfToken: cookie length=${cookies.length}, empty=${cookies.isEmpty()}")
            if (cookies.isEmpty()) {
                Timber.w("CsrfToken: no web cookie stored, cannot fetch token")
                return null
            }
            val request = Request.Builder()
                .url("https://www.pixiv.net/")
                .addHeader("Cookie", cookies)
                .addHeader("User-Agent", ClientManager.WEB_USER_AGENT)
                .build()
            val response = buildClient().newCall(request).execute()
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

        Timber.w("CsrfToken: no token found in HTML (length=${html.length})")
        return null
    }

    fun clear() {
        cached = null
        store.removeValueForKey(KEY_CSRF)
    }
}
