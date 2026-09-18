package ceui.lisa.http

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.GET
import java.util.concurrent.TimeUnit

/**
 * [CfBlockDetector] 走**真实 Retrofit 链路**的集成回归。
 *
 * `CfBlockDetectorTest` 验的是纯函数；这里验的是那条纯函数**之前**的链路 ——
 * 也就是 Retrofit 怎么把响应交到 `ErrorCtrl` / `getHumanReadableMessage` 手里。
 *
 * 这里钉住两条实测结论，都是「靠推理会推错」的那种：
 *
 * 1. **Retrofit 会把响应头原样带进 `HttpException`** —— 判别的全部依据都在头里，
 *    转换层只要丢掉它们，整套鉴别就会静默失效（表现为「永远不弹窗」）。
 * 2. **Retrofit 会把 raw 响应的 body 换成 `NoContentResponseBody`（长度 0）** ——
 *    正文只保留在 `errorBody()` 里。所以在 `HttpException.response().raw()` 上
 *    `peekBody` **永远嗅不到东西**，正文通路若照那条路写就是死代码。
 *    这条是实测踩出来的（第一版实现就是这么写的，5 条用例一起红）。
 *
 * 另：必须用 **suspend** 端点而不是 `Call<T>.execute()` —— 后者对 403 不抛
 * `HttpException`，而 App 里所有 API 都是 suspend 形态。
 */
class CfBlockHttpChainTest {

    private lateinit var server: MockWebServer

    /**
     * 只声明一个端点：Retrofit 的 [ResponseBody] 内置转换器，不需要 Gson。
     *
     * 刻意用 **suspend** 而不是 `Call<T>.execute()` —— 两条路对非 2xx 的处理不一样：
     * suspend 版由 Retrofit 抛 `HttpException`，而这正是 App 里所有 API 的形态
     * （`ErrorCtrl` / `getHumanReadableMessage` 收到的就是它）。
     * 实测 `Call<T>.execute()` 对 403 **不抛**，用它测不到真实链路。
     */
    interface ProbeApi {
        @GET("v1/illust/detail")
        suspend fun detail(): ResponseBody
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── 假设 1：头能穿过 Retrofit ─────────────────────────────────────

    @Test
    fun `Retrofit 把响应头原样带进 HttpException —— 判别依据不会在转换层丢掉`() {
        server.enqueue(cfBlockResponse())
        val e = expectHttpException()

        val headers = e.response()!!.raw().headers
        assertEquals("a3cf110fcb91d74f-NRT", headers["CF-RAY"])
        assertEquals("same-origin", headers["Referrer-Policy"])
        assertEquals("SAMEORIGIN", headers["X-Frame-Options"])
        assertEquals("cloudflare", headers["Server"])
        assertNull(
            "源站指纹头不该出现 —— 这条同时是 CF 判据成立的前提",
            headers[CfBlockDetector.HEADER_ORIGIN_TIME],
        )
    }

    // ── 假设 2：嗅探不吃正文 ──────────────────────────────────────────

    @Test
    fun `Retrofit 把 raw 响应的正文换成空的 —— 正文只在 errorBody 里`() {
        // 回归守卫。Retrofit 的 parseResponse 会
        //   rawResponse.newBuilder().body(new NoContentResponseBody(...)).build()
        // —— 有意让响应能安全传递，正文只留在 errorBody()。谁要是把 CF 的正文嗅探改回
        // 「在 raw 上 peekBody」，这里会立刻红。
        server.enqueue(relayedCfBlockResponse())
        val e = expectHttpException()

        // 注意：contentLength() 报的仍是**原始长度**，但正文读不出来 ——
        // 「长度 > 0」根本不能用来判断正文可读，必须实际读一下。
        assertEquals(
            "长度会骗人：报的是原始长度",
            CF_PAGE_BODY.toByteArray(Charsets.UTF_8).size.toLong(),
            e.response()!!.raw().body!!.contentLength(),
        )

        // 实测在 raw 上读正文会抛 IllegalStateException("Cannot read raw response body of a
        // converted body.")。而 CfBlockDetector.peekBodySafely 会把任何异常吞掉返回 null
        // —— 于是正文通路**静默**失效，外部完全看不出来。这正是它当初逃过 22 条纯函数用例的原因。
        //
        // 这里不锁死「必须抛」还是「必须空」：两者都说明 raw 那条路拿不到正文。
        // 哪天 Retrofit 改成返回空 body，这条依然成立；真能读到内容了才需要重新评估。
        val rawPeek = runCatching { e.response()!!.raw().peekBody(64L * 1024).string() }
        val rawText = rawPeek.getOrNull()
        assertTrue(
            "raw 上必须拿不到正文，实际拿到「$rawText」—— " +
                "若这条挂了说明 Retrofit 行为变了，正文通路可以改回 raw",
            rawPeek.isFailure || rawText.isNullOrEmpty(),
        )
        assertEquals("正文完整地留在 errorBody() 里", CF_PAGE_BODY, e.response()!!.errorBody()!!.string())
    }

    @Test
    fun `正文通路 —— 头不像 CF 时，靠 errorBody 的正文仍能判为 CF`() {
        server.enqueue(relayedCfBlockResponse())
        val e = expectHttpException()

        val headers = e.response()!!.raw().headers
        val body = e.response()!!.errorBody()!!.string()

        assertTrue("应靠正文标记命中", CfBlockDetector.isCfBlock(403, headers, body))
        assertFalse(
            "反面对照：不传正文时头通路判不出来 —— 说明正文在这一场景里是必需的",
            CfBlockDetector.isCfBlock(403, headers, null),
        )
    }

    @Test
    fun `头通路命中时不需要正文`() {
        server.enqueue(cfBlockResponse())
        val e = expectHttpException()

        assertTrue(CfBlockDetector.isCfBlock(403, e.response()!!.raw().headers, null))
    }

    // ── 端到端判定 ────────────────────────────────────────────────────

    @Test
    fun `CF 拦截 403 走完整链路被判为 CF`() {
        server.enqueue(cfBlockResponse())
        val e = expectHttpException()

        assertTrue(CfBlockDetector.isCfBlock(e))
        assertNotNull("应能解出原始响应供引导弹窗判断方向", CfBlockDetector.rawResponseOf(e))
        assertEquals(403, CfBlockDetector.rawResponseOf(e)!!.code)
    }

    @Test
    fun `源站 403 走同一条链路不判为 CF`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setHeader("Server", "cloudflare")
                .setHeader("x-userid", "128734570")
                .setHeader("x-envoy-upstream-service-time", "10")
                .setHeader("strict-transport-security", "max-age=31536000")
                .setHeader("cf-cache-status", "DYNAMIC")
                .setHeader("CF-RAY", "a3cf47df78b1e366-NRT")
                .setBody(ORIGIN_403_BODY),
        )
        val e = expectHttpException()

        assertFalse(CfBlockDetector.isCfBlock(e))
        assertEquals(ORIGIN_403_BODY, e.response()!!.errorBody()!!.string())
    }

    @Test
    fun `2xx 不参与判定，正文照旧可读`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(ORIGIN_403_BODY),
        )
        assertEquals(ORIGIN_403_BODY, runBlocking { api().detail().string() })
    }

    // ── 工具 ──────────────────────────────────────────────────────────

    private fun api(): ProbeApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())
        .build()
        .create(ProbeApi::class.java)

    /** 跑一次请求并把 [HttpException] 捞出来；真成功返回了就说明用例本身写错了。 */
    private fun expectHttpException(): HttpException {
        val e = runBlocking { runCatching { api().detail() } }.exceptionOrNull()
        return e as? HttpException
            ?: throw AssertionError("预期 Retrofit 抛 HttpException，实际：${e ?: "成功返回"}")
    }

    /**
     * 自建源把上游 CF 拦截页**原样转发**回来时的形态：正文是拦截页，但响应头是代理自己的
     * （没有 CF 定制页的 `Referrer-Policy` / `X-Frame-Options`），头通路判不出来，
     * 只能靠正文。这是正文通路存在的唯一理由。
     */
    private fun relayedCfBlockResponse(): MockResponse = MockResponse()
        .setResponseCode(403)
        .setHeader("Content-Type", "text/html; charset=UTF-8")
        .setHeader("Server", "nginx")
        .setBody(CF_PAGE_BODY)

    /** CF 拦截 403 的实测头集合（见根目录 CF403样本-响应头.txt）。 */
    private fun cfBlockResponse(): MockResponse = MockResponse()
        .setResponseCode(403)
        .setHeader("Content-Type", "text/html; charset=UTF-8")
        .setHeader(
            "Cache-Control",
            "private, max-age=0, no-store, no-cache, must-revalidate, post-check=0, pre-check=0",
        )
        .setHeader("Referrer-Policy", "same-origin")
        .setHeader("X-Frame-Options", "SAMEORIGIN")
        .setHeader("Server", "cloudflare")
        .setHeader("CF-RAY", "a3cf110fcb91d74f-NRT")
        .setBody(CF_PAGE_BODY)

    companion object {
        /**
         * 拦截页正文的真实标记（取自 CF403样本-响应体.html）。
         *
         * 用 Kotlin 原样字符串（`"""`）而不是逐字符转义 —— 里面全是 HTML 的引号，转义写极易出错。
         * 判据只认那几个标记，引号长什么样不影响判定。
         */
        const val CF_PAGE_BODY: String =
            """<html><head><title>pixiv</title></head><body>""" +
                """<div data-trans-key="block_waf:title">ブロックされました</div>""" +
                """<script>gtag("event","block_waf",{event_category:"cloudflare-custom-page"});</script>""" +
                """</body></html>"""

        /** 源站 403 的真实正文（见根目录 源站403样本.txt）。 */
        const val ORIGIN_403_BODY: String =
            """{"error":{"user_message":"公開制限エラーです。","message":"","reason":"","user_message_details":{}}}"""
    }
}
