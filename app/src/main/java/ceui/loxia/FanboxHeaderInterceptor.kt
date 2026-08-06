package ceui.loxia

import android.webkit.CookieManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * FANBOX 的请求头。
 *
 * - `Origin` 是**硬性要求**:少了它 api.fanbox.cc 一律 400,跟登不登录无关。
 * - cookie 直接从 WebView 的 [CookieManager] 现取,不另存一份 —— 用户在
 *   FANBOX 页面里登录/登出,这边下一个请求就同步了,不存在两份状态对不上的问题。
 *   （对比 pixiv 网页那套:它必须存进 MMKV 是因为 OkHttp 侧要在没打开过 WebView 时也能用。）
 */
class FanboxHeaderInterceptor : Interceptor {

    companion object {
        const val FANBOX_ORIGIN = "https://www.fanbox.cc"
        private const val SESSION_COOKIE = "FANBOXSESSID="

        /** WebView 里当前的 fanbox cookie 串;没初始化过 WebView 时返回 null 而不是抛。 */
        fun currentCookie(): String? =
            runCatching { CookieManager.getInstance().getCookie(FANBOX_ORIGIN) }.getOrNull()

        /** 是否已在 WebView 里登录过 FANBOX。未登录时 post.listHome 会 401。 */
        fun hasSession(): Boolean = currentCookie()?.contains(SESSION_COOKIE) == true
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("Origin", FANBOX_ORIGIN)
            .header("Referer", "$FANBOX_ORIGIN/")
            .header("Accept", "application/json")
            // cf_clearance 绑 UA,和 WebView 保持同一个,否则 Cloudflare 会挑出来
            .header("User-Agent", ClientManager.WEB_USER_AGENT)
        currentCookie()?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
        return chain.proceed(builder.build())
    }
}
