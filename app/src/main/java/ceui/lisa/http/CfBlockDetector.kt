package ceui.lisa.http

import okhttp3.Headers
import okhttp3.Response
import timber.log.Timber

/**
 * 鉴别「Cloudflare 边缘答的 403」与「pixiv 源站答的 403」。
 *
 * ## 为什么不能用 CF-RAY / Server 判
 *
 * 常见建议是「响应里没有 `CF-RAY` 就是源站的真 403」。**这条对 app-api 完全不成立**：
 * app-api 常年就在 Cloudflare CDN 后面，正常 200、源站 4xx、CF 拦截 403，三种情况
 * 都带 `CF-RAY` 和 `Server: cloudflare`。实测（2026-09-18）：
 *
 * ```
 * 200/400/404 源站应答 → CF-RAY: a3cf...  Server: cloudflare
 * 403 CF 拦截          → CF-RAY: a3cf...  Server: cloudflare   ← 一模一样
 * ```
 *
 * ## 真正的判据：请求有没有走到源站
 *
 * app-api 的源站前面是一层 envoy，它会注入 [HEADER_ORIGIN_TIME]；CF 也会加
 * [HEADER_CF_CACHE_STATUS]。**只要这两个头在，请求就真的到了源站**——
 * 无论状态码和 Content-Type 是什么。
 *
 * 两个头都不在，才可能是 CF 边缘直接答的。
 *
 * ## 为什么 Content-Type 也不能单独当判据
 *
 * 直觉是「CF 拦截页是 HTML，源站错误是 JSON」。但实测发现**源站自己也会返回 HTML**：
 *
 * ```
 * GET /v1/novel/text?novel_id=1 → 404, Content-Type: text/html; charset=UTF-8
 *                                  且 x-envoy-upstream-service-time 在 ← 是源站
 * ```
 *
 * 所以 [Content-Type][HEADER_CONTENT_TYPE] 只用来**提高确信度**，不作为独立依据。
 *
 * ## 两条独立的通路
 *
 * - **头通路**：403 + 无源站指纹 + CF 拦截页的头特征。不需要读 body。
 * - **正文通路**：正文含 CF 拦截页标记。用来覆盖「自建源把上游拦截页原样转发回来」
 *   的情形——那种响应带着代理自己的头，头通路会漏判。
 *
 * 两条通路**任一命中**即认定。头通路负责快，正文通路负责覆盖转发场景。
 *
 * ## 本类刻意做成纯函数
 *
 * 吃 [okhttp3.Response]（或裸的 code + headers + body 片段），不依赖 Android、不碰
 * 拦截器链、不持有状态。这样三个调用面可以共用同一份判别：
 *
 * | 调用面 | 入口 |
 * |---|---|
 * | [ErrorCtrl] / [ceui.pixiv.ui.common.getHumanReadableMessage] | `HttpException.response().raw()` |
 * | 网络测试页 | 它自己手上那个 `Response` |
 *
 * 也正因为判别不需要改拦截器，**不存在「拦截器插在 CronetInterceptor 之后会变成死代码」
 * 那个坑**：`CronetInterceptor` 对白名单 host 根本不调 `chain.proceed()`。
 */
object CfBlockDetector {

    /** 源站指纹：pixiv 前置的 envoy 注入。在 = 请求真的到了源站。 */
    const val HEADER_ORIGIN_TIME = "x-envoy-upstream-service-time"

    /** CF 回源缓存状态。CF 边缘拦截时不会有。 */
    const val HEADER_CF_CACHE_STATUS = "cf-cache-status"

    private const val HEADER_CONTENT_TYPE = "content-type"
    private const val HEADER_REFERRER_POLICY = "referrer-policy"
    private const val HEADER_FRAME_OPTIONS = "x-frame-options"

    /**
     * 拦截页正文标记，任一击中即认定。分两组，因为它们的**位置**差了两个数量级：
     *
     * **前段组**（实测字节偏移，都在真实拦截页前 8 KB 内）：
     * `_challenge_` @3961、`_error_1xxx` @5074、`cf-error-details` @5727、`cferror_details` @5793。
     * 它们来自 CF 定制页的内联样式表，页面一开始就是这些。
     *
     * **后段组**（实测偏移）：`block_waf` @367953、`data-trans-key` @372129、
     * `cloudflare-custom-page` @372433、`/cdn-cgi/challenge-platform/` @373336。
     *
     * ⚠️ **两组都要留。** 整页 374 KB，前面 367 KB 全是内联 CSS + GTM 脚本，
     * 真正的页面标记挤在最后几 KB。只留后段组的话，[BODY_PEEK_BYTES] 窗口内的嗅探
     * **一个都命中不了** —— 这是实测踩出来的坑，`CfBlockDetectorTest` 里的
     * 「真实拦截页的头部窗口」用例就是它的回归守卫。
     *
     * ⚠️ 不要换成 `Just a moment` / `Attention Required` / `error-code` /
     * `Cloudflare Ray ID` —— pixiv 用的是**定制**拦截页，这些教科书标记一个都没有。
     */
    private val PAGE_MARKERS = arrayOf(
        // 前段组：前 8 KB 内必然命中，正文通路真正依赖的是这一组
        "cf-error-details",
        "cferror_details",
        "_error_1xxx",
        "_challenge_",
        // 后段组：窗口足够大时才可能命中，留作冗余
        "block_waf",
        "cloudflare-custom-page",
        "data-trans-key",
        "/cdn-cgi/challenge-platform/",
    )

    /**
     * 正文嗅探上限。
     *
     * 取值依据：真实拦截页的前段标记最早出现在 ~4 KB、最晚 ~7.8 KB，64 KB 给了
     * 8 倍余量，又不至于为一次判定缓冲整张 374 KB 的页面。
     *
     * 代价可控：`peekBody` 缓冲的字节**不会重复下载** —— 调用方随后照旧要读完整个正文，
     * 已缓冲的部分直接从内存取。
     */
    const val BODY_PEEK_BYTES = 64L * 1024

    /**
     * 只读响应头即可判定的版本（**不读 body**）。
     *
     * 给「手上只有 [Headers]、或者不想触发任何 I/O」的调用方用。
     */
    @JvmStatic
    @JvmOverloads
    fun isCfBlock(code: Int, headers: Headers, bodyPeek: CharSequence? = null): Boolean {
        if (code != 403) return false
        // 到了源站就不是 CF 拦的。这一条是唯一「单条即可定论」的信号。
        if (reachedOrigin(headers)) return false
        return hasCfBlockHeaderSignature(headers) || bodyHasCfBlockMarkers(bodyPeek)
    }

    /**
     * 直接吃一个 okhttp [Response]：先看头，头不够确定时再嗅一截正文。
     *
     * [Response.peekBody] 是**非破坏性**的（读进底层 buffer 但不推进消费位置）。
     *
     * ⚠️ **不要把它用在 `HttpException.response().raw()` 上。**
     * Retrofit 的 `parseResponse` 会把 raw 响应的 body 替换成 `NoContentResponseBody`
     * —— 它有意让响应可以安全地传递下去，真正的正文只保留在 `errorBody()` 里。
     *
     * 在 raw 上读正文会抛 `IllegalStateException("Cannot read raw response body of a
     * converted body.")`，而 [peekBodySafely] 会把异常吞掉返回 null。结果是正文通路
     * **静默失效**：不崩、不报错，只是永远判不出「代理转发的拦截页」。实测踩到过，
     * 回归守卫见 `CfBlockHttpChainTest`。
     *
     * 走 Retrofit 的调用方请改用 [isCfBlock] 的三参重载，把 `errorBody()` 的正文传进来；
     * 本重载适合手上是**真** okhttp 响应的场景（例如网络测试页自己发的请求）。
     */
    @JvmStatic
    fun isCfBlock(response: Response): Boolean {
        if (response.code != 403) return false
        if (reachedOrigin(response.headers)) return false
        if (hasCfBlockHeaderSignature(response.headers)) return true
        return bodyHasCfBlockMarkers(peekBodySafely(response))
    }

    /**
     * 异常版入口：把 [retrofit2.HttpException] 解到最底层的 okhttp 响应再判。
     *
     * 非 HTTP 异常（断网 / 超时 / 反序列化）取不到响应头，一律返回 false ——
     * CF 判定要真实的响应头，猜不得。
     */
    @JvmStatic
    fun isCfBlock(e: Throwable): Boolean {
        val raw = rawResponseOf(e) ?: return false
        return isCfBlock(raw)
    }

    /**
     * 从异常里取出最底层的 okhttp 响应。取不到（或不是 HTTP 异常）返回 null。
     *
     * 给需要同时拿到「是不是 CF 拦的」和「是哪台主机答的」的调用方用 ——
     * 后者决定引导文案的方向和 trace 打哪台机。
     */
    @JvmStatic
    fun rawResponseOf(e: Throwable): Response? {
        val http = e as? retrofit2.HttpException ?: return null
        return try {
            http.response()?.raw()
        } catch (ex: RuntimeException) {
            Timber.tag("CfBlockDetector").w(ex, "取原始响应失败")
            null
        }
    }

    /**
     * 脱敏：**隐匿最后一位**。
     *
     * - IPv4 `151.244.134.189` → `151.244.134.*`
     * - IPv6 只留前三段 `2400:8902::f03c:…` → `2400:8902::*`
     *
     * 这个粒度足够让用户认出「是不是自己正在用的那条线路」，又不足以把完整出口地址
     * 摆进一张可能被截图分享的弹窗里。
     *
     * 放在这里而不是 [CfBlockGuide]：它是纯字符串处理，不碰 Android，跟着本类一起
     * 享受纯 JVM 单测（同 [AppApiProxyInterceptor.normalizeBase] 的做法）。
     */
    @JvmStatic
    fun maskIp(raw: String): String {
        val ip = raw.trim()
        if (ip.isEmpty()) return ip

        val octets = ip.split('.')
        if (octets.size == 4 && octets.all { part -> part.isNotEmpty() && part.all(Char::isDigit) }) {
            return "${octets[0]}.${octets[1]}.${octets[2]}.*"
        }

        val hextets = ip.split(':')
        if (hextets.size >= 3) {
            return hextets.take(3).joinToString(":") + ":*"
        }
        return "*"
    }

    /**
     * 请求是否真的到了源站。任一源站侧的头存在即为真。
     *
     * 这是本方案里唯一的「单条即可定论」信号：实测 6 个源站响应样本无一例外都带
     * [HEADER_ORIGIN_TIME]，而 CF 拦截样本一律不带。
     */
    fun reachedOrigin(headers: Headers): Boolean =
        headers[HEADER_ORIGIN_TIME] != null || headers[HEADER_CF_CACHE_STATUS] != null

    /**
     * CF 拦截页的头特征。
     *
     * 实测 CF 拦截 403 的固定组合（3 个样本完全一致）：
     * `Content-Type: text/html; charset=UTF-8` + `Referrer-Policy: same-origin` +
     * `X-Frame-Options: SAMEORIGIN` + `Cache-Control: …no-store…`。
     *
     * 源站应答里这三个头一个都没有，所以要求「HTML **且** 至少一个 CF 页面头」。
     */
    private fun hasCfBlockHeaderSignature(headers: Headers): Boolean {
        val contentType = headers[HEADER_CONTENT_TYPE] ?: return false
        if (!contentType.startsWith("text/html", ignoreCase = true)) return false
        val referrerPolicy = headers[HEADER_REFERRER_POLICY]
        val frameOptions = headers[HEADER_FRAME_OPTIONS]
        return referrerPolicy.equals("same-origin", ignoreCase = true) ||
            frameOptions.equals("SAMEORIGIN", ignoreCase = true)
    }

    /**
     * 正文里是否出现 CF 拦截页标记。大小写不敏感。
     *
     * 用 `contains(ignoreCase = true)` 而不是先把整段 lowercase：拦截页有 374 KB，
     * 复制一份小写副本纯属浪费，而这函数在错误路径上每次都要跑。
     *
     * **传完整正文，不要只传开头。** 后段标记（`block_waf` 等）实测落在第 367953 字节，
     * 截开头会漏；调用方手上已经有整份正文时（`errorBody()`）直接传进来即可。
     */
    fun bodyHasCfBlockMarkers(bodyPeek: CharSequence?): Boolean {
        if (bodyPeek.isNullOrEmpty()) return false
        return PAGE_MARKERS.any { bodyPeek.contains(it, ignoreCase = true) }
    }

    /**
     * 尽量嗅一截正文。**任何失败都当作「没嗅到」**——判别可以退化到头通路，
     * 但绝不能让一个提示把调用方崩掉。
     *
     * body 已被读完 / 已关闭时 `peekBody` 会抛，这里兜住。
     */
    private fun peekBodySafely(response: Response): String? = try {
        response.peekBody(BODY_PEEK_BYTES).string()
    } catch (e: Exception) {
        Timber.tag("CfBlockDetector").d(e, "peekBody 失败，退化到头通路判定")
        null
    }
}
