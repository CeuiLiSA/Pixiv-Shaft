package ceui.pixiv.ui.translate

import ceui.pixiv.shaftapi.PixshaftApi
import ceui.pixiv.shaftapi.TranslateResult
import ceui.pixiv.shaftapi.translateTexts
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [CloudTranslator] 的协议层测试：MockWebServer 假扮 pixshaft-api，走 [CloudTranslator.translateBatchWith]
 * 显式传 api / uid，不碰 Shaft.sSettings、SessionManager 和远程配置。
 */
class CloudTranslatorTest {

    private lateinit var server: MockWebServer
    private lateinit var api: PixshaftApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(Gson()))
            .build()
            .create(PixshaftApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `请求体只有 uid texts lang，译文按序回填并触发回调`() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"uid":7,"translations":["你好","谢谢"],"serverTime":1000,"plan":{"key":"free"},
                   "quotas":[{"key":"session","scope":"uid_5h","windowHours":5,"used":8,"max":20000,"remaining":19992,"resetsAt":2000}]}""",
            ),
        )
        val items = CopyOnWriteArrayList<Pair<Int, String>>()
        var progress = 0 to 0
        var requestSent = 0
        var phase: AiTranslatePhase? = null

        val out = CloudTranslator.translateBatchWith(
            api, 7L, listOf("こんにちは", "ありがとう"), "zh",
            onItem = { i, t -> items.add(i to t) },
            onProgress = { d, t -> progress = d to t },
            onPhase = { phase = it },
            onRequestSent = { requestSent++ },
        )

        assertEquals(listOf("你好", "谢谢"), out)
        assertEquals(listOf(0 to "你好", 1 to "谢谢"), items.toList())
        assertEquals(2 to 2, progress)
        assertEquals(1, requestSent)
        assertEquals(AiTranslatePhase.GENERATING, phase)

        val req = server.takeRequest()
        assertEquals("/v1/account/translate", req.path)
        assertEquals("""{"uid":7,"texts":["こんにちは","ありがとう"],"lang":"zh-CN"}""", req.body.readUtf8())
    }

    @Test
    fun `429 额度桶满抛 CloudTranslateQuotaException 并带服务端时钟算出的恢复时间`() = runBlocking {
        server.enqueue(
            json(
                429,
                """{"error":"rate_limited","scope":"uid_5h","retryAfterSeconds":3600,"serverTime":10000,
                   "quotas":[{"key":"session","scope":"uid_5h","windowHours":5,"used":20000,"max":20000,"remaining":0,"resetsAt":3610000}]}""",
            ),
        )
        try {
            CloudTranslator.translateBatchWith(api, 7L, listOf("a"), "en")
            fail("expected CloudTranslateQuotaException")
        } catch (e: CloudTranslateQuotaException) {
            assertEquals("uid_5h", e.scope)
            assertEquals(3_600_000L, e.resetInMs)
        }
    }

    @Test
    fun `每分钟限流不是额度问题，按普通 CloudTranslateException 报`() = runBlocking {
        server.enqueue(json(429, """{"error":"rate_limited","scope":"ip","retryAfterSeconds":12}"""))
        try {
            CloudTranslator.translateBatchWith(api, 7L, listOf("a"), "en")
            fail("expected CloudTranslateException")
        } catch (e: CloudTranslateException) {
            assertEquals(429, e.code)
            assertEquals("rate_limited:ip", e.message)
        }
    }

    @Test
    fun `服务端错误码原样带到异常 message`() = runBlocking {
        server.enqueue(json(504, """{"error":"upstream_timeout"}"""))
        try {
            CloudTranslator.translateBatchWith(api, 7L, listOf("a"), "en")
            fail("expected CloudTranslateException")
        } catch (e: CloudTranslateException) {
            assertEquals(504, e.code)
            assertEquals("upstream_timeout", e.message)
        }
    }

    @Test
    fun `translateTexts 把 503 translate_disabled 映射成 Disabled，长度不符算脏响应`() = runBlocking {
        server.enqueue(json(503, """{"error":"translate_disabled"}"""))
        assertTrue(api.translateTexts(7L, listOf("a"), "en") is TranslateResult.Disabled)

        server.enqueue(json(200, """{"translations":["only one"]}"""))
        assertTrue(api.translateTexts(7L, listOf("a", "b"), "en") is TranslateResult.InvalidResponse)

        server.enqueue(json(502, """{"error":"upstream_bad_reply"}"""))
        val failure = api.translateTexts(7L, listOf("a"), "en") as TranslateResult.HttpFailure
        assertEquals(502, failure.status)
        assertEquals("upstream_bad_reply", failure.error)
    }

    @Test
    fun `gtx 语言码映射成服务端白名单`() {
        assertEquals("zh-CN", CloudTranslator.serverLangOf("zh"))
        assertEquals("zh-CN", CloudTranslator.serverLangOf("zh-CN"))
        assertEquals("zh-TW", CloudTranslator.serverLangOf("zh-TW"))
        assertEquals("ja", CloudTranslator.serverLangOf("ja"))
    }
}
