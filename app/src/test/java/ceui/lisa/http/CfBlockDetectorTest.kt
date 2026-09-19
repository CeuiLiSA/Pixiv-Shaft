package ceui.lisa.http

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CfBlockDetector] 的纯 JVM 回归。
 *
 * 样本全部来自 2026-09-18 对 `app-api.pixiv.net` 的实测抓取（真实响应头与拦截页正文），
 * 不是构造出来的理想数据。
 * 这组用例的价值就在于把「实测长什么样」钉住，尤其是那条反直觉的：
 * **源站自己也会返回 text/html**。
 */
class CfBlockDetectorTest {

    // ── 实测样本 ──────────────────────────────────────────────────────

    /** CF 边缘拦截 403。触发方式：`?illust_id=1 UNION SELECT …`。 */
    private val cfBlockHeaders = headersOf(
        "Date" to "Fri, 18 Sep 2026 08:44:04 GMT",
        "Content-Type" to "text/html; charset=UTF-8",
        "Transfer-Encoding" to "chunked",
        "Connection" to "keep-alive",
        "Cache-Control" to
            "private, max-age=0, no-store, no-cache, must-revalidate, post-check=0, pre-check=0",
        "Expires" to "Thu, 01 Jan 1970 00:00:01 GMT",
        "Referrer-Policy" to "same-origin",
        "X-Frame-Options" to "SAMEORIGIN",
        "Server" to "cloudflare",
        "CF-RAY" to "a3cf110fcb91d74f-NRT",
    )

    /** 源站 400。`/v1/illust/detail?illust_id=1`，无凭证。 */
    private val origin400Headers = headersOf(
        "Content-Type" to "application/json; charset=utf-8",
        "Content-Length" to "191",
        "Server" to "cloudflare",
        "x-content-type-options" to "nosniff",
        "x-envoy-upstream-service-time" to "2",
        "strict-transport-security" to "max-age=31536000",
        "cf-cache-status" to "DYNAMIC",
        "CF-RAY" to "a3cf10527ccfeff8-NRT",
    )

    /**
     * 源站 404，**而且正文是 HTML**。`/v1/novel/text?novel_id=1`。
     *
     * 这是本套用例里最重要的一条：它和 CF 拦截页一样是 `text/html; charset=UTF-8`、
     * 一样是 `Server: cloudflare`、一样带 `CF-RAY`，只差源站指纹头。
     * 光看 Content-Type 会把它误判成 CF 拦截。
     */
    private val origin404HtmlHeaders = headersOf(
        "Content-Type" to "text/html; charset=UTF-8",
        "Transfer-Encoding" to "chunked",
        "Server" to "cloudflare",
        "x-envoy-upstream-service-time" to "2",
        "strict-transport-security" to "max-age=31536000",
        "cf-cache-status" to "DYNAMIC",
        "CF-RAY" to "a3cf15e28970166d-NRT",
    )

    /**
     * 源站 403 —— **实测样本**。
     *
     * 触发方式：有效 AT + `GET /v1/ugoira/metadata?illust_id=88561237`
     * （88561237 是一个「公開制限」作品，拿不到就回 403 而不是打码 200）。
     *
     * 与 CF 拦截页的头集合**完全不相交**：源站这边有 `x-envoy-upstream-service-time`、
     * `cf-cache-status`、`strict-transport-security`、`x-userid`、`vary`；
     * CF 那边这些一个都没有。
     */
    private val origin403Headers = headersOf(
        "Date" to "Fri, 18 Sep 2026 09:21:29 GMT",
        "Content-Type" to "application/json; charset=utf-8",
        "Content-Length" to "140",
        "Connection" to "keep-alive",
        "Server" to "cloudflare",
        "x-userid" to "128734570",
        "vary" to "X-UserId",
        "x-content-type-options" to "nosniff",
        "x-envoy-upstream-service-time" to "10",
        "strict-transport-security" to "max-age=31536000",
        "cf-cache-status" to "DYNAMIC",
        "CF-RAY" to "a3cf47df78b1e366-NRT",
    )

    /** 源站 403 的真实正文（`公開制限エラーです。`）。 */
    private val origin403Body =
        """{"error":{"user_message":"公開制限エラーです。","message":"","reason":"","user_message_details":{}}}"""

    /** 拦截页正文里的真实标记（取自实测抓取的 CF 定制拦截页）。 */
    private val cfPageBody =
        """<html><head><title>pixiv</title></head><body>""" +
            """<div data-trans-key="block_waf:title">ブロックされました</div>""" +
            """<script>gtag("event","block_waf",{event_category:"cloudflare-custom-page"});</script>""" +
            """</body></html>"""

    /** 源站错误正文（`/v1/illust/detail` 无凭证）。 */
    private val originJsonBody =
        """{"error":{"user_message":"","message":"Error occurred at the OAuth process. """ +
            """Please check your Access Token to fix this. Error Message: invalid_request","reason":"","user_message_details":{}}}"""

    // ── 判据：请求有没有走到源站 ───────────────────────────────────────

    @Test
    fun `CF 拦截 403 判为 CF`() {
        assertTrue(CfBlockDetector.isCfBlock(response(403, cfBlockHeaders, cfPageBody)))
    }

    @Test
    fun `源站 403 不判为 CF`() {
        assertFalse(CfBlockDetector.isCfBlock(response(403, origin403Headers, origin403Body)))
    }

    @Test
    fun `源站 403 与 CF 拦截 403 的头集合完全不相交`() {
        // 实测对照。两边都是 403、都是 Server: cloudflare、都带 CF-RAY —— 靠这两样
        // 区分不了（这正是「没有 CF-RAY 就是源站真 403」那条建议失效的原因）。
        // 真正分叉的是下面这两组头。
        listOf("x-envoy-upstream-service-time", "cf-cache-status", "strict-transport-security")
            .forEach { assertNotNull("源站 403 应带 $it", origin403Headers[it]) }
        listOf("referrer-policy", "x-frame-options")
            .forEach { assertNull("源站 403 不该带 $it", origin403Headers[it]) }

        listOf("referrer-policy", "x-frame-options")
            .forEach { assertNotNull("CF 拦截 403 应带 $it", cfBlockHeaders[it]) }
        listOf("x-envoy-upstream-service-time", "cf-cache-status", "strict-transport-security")
            .forEach { assertNull("CF 拦截 403 不该带 $it", cfBlockHeaders[it]) }
    }

    @Test
    fun `源站返回 HTML 的 404 不判为 CF —— Content-Type 不是判据`() {
        assertFalse(CfBlockDetector.isCfBlock(response(404, origin404HtmlHeaders, "<html></html>")))
    }

    @Test
    fun `源站 400 不判为 CF`() {
        assertFalse(CfBlockDetector.isCfBlock(response(400, origin400Headers, originJsonBody)))
    }

    @Test
    fun `CF-RAY 与 Server cloudflare 在两组响应里都在 —— 所以不能拿它们当判据`() {
        // 这正是「没有 CF-RAY 就是源站真 403」那条建议对 app-api 失效的原因：
        // 四种组合里 CF-RAY / Server 完全一致，只有源站指纹头在分叉。
        assertEquals("a3cf110fcb91d74f-NRT", cfBlockHeaders["CF-RAY"])
        assertEquals("a3cf10527ccfeff8-NRT", origin400Headers["CF-RAY"])
        assertEquals("cloudflare", cfBlockHeaders["Server"])
        assertEquals("cloudflare", origin400Headers["Server"])

        assertTrue(CfBlockDetector.isCfBlock(response(403, cfBlockHeaders, cfPageBody)))
        assertFalse(CfBlockDetector.isCfBlock(response(403, origin400Headers, originJsonBody)))
    }

    @Test
    fun `源站指纹只要有一个在就不算 CF`() {
        assertTrue(CfBlockDetector.reachedOrigin(origin400Headers))
        assertTrue(CfBlockDetector.reachedOrigin(origin404HtmlHeaders))
        assertFalse(CfBlockDetector.reachedOrigin(cfBlockHeaders))

        // 只有 cf-cache-status、没有 x-envoy 也算到了源站
        val onlyCacheStatus = headersOf(
            "Content-Type" to "text/html; charset=UTF-8",
            "Referrer-Policy" to "same-origin",
            "cf-cache-status" to "DYNAMIC",
        )
        assertTrue(CfBlockDetector.reachedOrigin(onlyCacheStatus))
        assertFalse(CfBlockDetector.isCfBlock(response(403, onlyCacheStatus, cfPageBody)))
    }

    @Test
    fun `非 403 一律不判为 CF`() {
        assertFalse(CfBlockDetector.isCfBlock(response(200, cfBlockHeaders, cfPageBody)))
        assertFalse(CfBlockDetector.isCfBlock(response(503, cfBlockHeaders, cfPageBody)))
    }

    // ── 判据：头通路 / 正文通路 ────────────────────────────────────────

    @Test
    fun `头通路——HTML 且带 CF 页面头即命中，不需要读正文`() {
        assertTrue(CfBlockDetector.isCfBlock(403, cfBlockHeaders))
        assertTrue(CfBlockDetector.isCfBlock(403, cfBlockHeaders, null))
    }

    @Test
    fun `头通路——HTML 但没有 CF 页面头就不命中`() {
        // 自建源把拦截页转发回来时可能只剩 Content-Type，头通路会漏；
        // 这正是需要正文通路兜底的情形。
        val bare = headersOf(
            "Content-Type" to "text/html; charset=UTF-8",
            "Server" to "nginx",
        )
        assertFalse(CfBlockDetector.isCfBlock(403, bare, null))
    }

    @Test
    fun `正文通路——头不像 CF，但正文有拦截页标记，仍判为 CF`() {
        val relayed = headersOf(
            "Content-Type" to "text/html; charset=UTF-8",
            "Server" to "nginx",
        )
        assertTrue(CfBlockDetector.isCfBlock(403, relayed, cfPageBody))
        assertTrue(CfBlockDetector.isCfBlock(response(403, relayed, cfPageBody)))
    }

    @Test
    fun `正文通路——源站 JSON 错误体不含任何标记，不误伤`() {
        assertFalse(CfBlockDetector.bodyHasCfBlockMarkers(originJsonBody))
        assertFalse(CfBlockDetector.bodyHasCfBlockMarkers(""))
        assertFalse(CfBlockDetector.bodyHasCfBlockMarkers(null))

        val relayed = headersOf(
            "Content-Type" to "text/html; charset=UTF-8",
            "Server" to "nginx",
        )
        assertFalse(CfBlockDetector.isCfBlock(403, relayed, originJsonBody))
    }

    @Test
    fun `正文通路——四个标记逐个都能单独命中`() {
        listOf(
            "block_waf",
            "cloudflare-custom-page",
            "data-trans-key",
            "/cdn-cgi/challenge-platform/",
        ).forEach { marker ->
            assertTrue("marker=$marker", CfBlockDetector.bodyHasCfBlockMarkers("<p>$marker</p>"))
        }
    }

    @Test
    fun `正文通路——教科书标记在 pixiv 的定制页里不存在，不该被当成判据`() {
        // pixiv 用的是 CF 定制拦截页，这些默认页标记一个都没有。
        listOf(
            "Just a moment",
            "Attention Required",
            "Sorry, you have been blocked",
            "Cloudflare Ray ID",
            "error-code: 403",
        ).forEach { marker ->
            assertFalse("marker=$marker", CfBlockDetector.bodyHasCfBlockMarkers("<p>$marker</p>"))
        }
    }

    @Test
    fun `正文通路——真实拦截页的头部窗口内就能命中标记`() {
        // 回归守卫。整页 374 KB，前面 367 KB 是内联 CSS + GTM 脚本，真正的页面标记
        // 挤在最后几 KB。早先的标记组最早也要到第 367953 字节才出现，在嗅探窗口内
        // 一个都命中不了 —— 正文通路形同虚设。
        //
        // 夹具 cf403_head_window.html 就是那张真实拦截页的前 8 KB。
        val head = javaClass.getResourceAsStream("/cf403_head_window.html")
            ?.bufferedReader()
            ?.use { it.readText() }
        assertNotNull("测试夹具 cf403_head_window.html 缺失", head)
        assertTrue(
            "真实页面的头部窗口里必须能命中至少一个标记，否则正文通路在真机上永远不触发",
            CfBlockDetector.bodyHasCfBlockMarkers(head),
        )
    }

    @Test
    fun `嗅探窗口必须覆盖得住标记所在的前段区域`() {
        // 实测前段标记最早 ~4 KB、最晚 ~7.8 KB；窗口小于 8 KB 就会漏。
        assertTrue(
            "BODY_PEEK_BYTES=${CfBlockDetector.BODY_PEEK_BYTES} 覆盖不住 8 KB 的头部窗口",
            CfBlockDetector.BODY_PEEK_BYTES >= 8L * 1024,
        )
    }

    // ── 从异常解包 ────────────────────────────────────────────────────

    @Test
    fun `非 HTTP 异常解不出响应，直接判否`() {
        assertFalse(CfBlockDetector.isCfBlock(java.io.IOException("connection reset")))
        assertFalse(CfBlockDetector.isCfBlock(RuntimeException("boom")))
        assertEquals(null, CfBlockDetector.rawResponseOf(java.io.IOException("nope")))
    }

    @Test
    fun `从 HttpException 能解出原始响应并判为 CF`() {
        val raw = response(403, cfBlockHeaders, cfPageBody)
        val http = retrofit2.HttpException(retrofit2.Response.error<Any>(raw.body!!, raw))
        val extracted = CfBlockDetector.rawResponseOf(http)
        assertNotNull(extracted)
        assertEquals(403, extracted!!.code)
        assertTrue(CfBlockDetector.isCfBlock(http))
    }

    // ── 脱敏：隐匿最后一位 ─────────────────────────────────────────────

    @Test
    fun `IPv4 隐匿最后一段`() {
        assertEquals("151.244.134.*", CfBlockDetector.maskIp("151.244.134.189"))
        assertEquals("203.0.113.*", CfBlockDetector.maskIp("203.0.113.45"))
        assertEquals("10.0.0.*", CfBlockDetector.maskIp("10.0.0.1"))
    }

    @Test
    fun `IPv4 前后的空白被裁掉`() {
        assertEquals("151.244.134.*", CfBlockDetector.maskIp("  151.244.134.189\n"))
    }

    @Test
    fun `IPv6 只留前三段`() {
        assertEquals("2400:8902::*", CfBlockDetector.maskIp("2400:8902::f03c:91ff:fe96:5b1d"))
        assertEquals("2001:0db8:85a3:*", CfBlockDetector.maskIp("2001:0db8:85a3:0000:0000:8a2e:0370:7334"))
    }

    @Test
    fun `空串与认不出的形状不崩，退到全遮蔽`() {
        assertEquals("", CfBlockDetector.maskIp(""))
        assertEquals("*", CfBlockDetector.maskIp("not-an-ip"))
    }

    // ── 工具 ──────────────────────────────────────────────────────────

    private fun headersOf(vararg pairs: Pair<String, String>): Headers {
        val builder = Headers.Builder()
        pairs.forEach { (name, value) -> builder.add(name, value) }
        return builder.build()
    }

    private fun response(code: Int, headers: Headers, body: String): Response =
        Response.Builder()
            .request(
                Request.Builder()
                    .url("https://app-api.pixiv.net/v1/illust/detail?illust_id=1")
                    .build()
            )
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .headers(headers)
            .body(body.toResponseBody("text/html; charset=UTF-8".toMediaTypeOrNull()))
            .build()
}
