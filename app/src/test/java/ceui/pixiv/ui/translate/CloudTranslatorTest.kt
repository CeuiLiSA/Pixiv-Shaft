package ceui.pixiv.ui.translate

import ceui.pixiv.shaftapi.PixshaftApi
import ceui.pixiv.shaftapi.CloudTranslateEngine
import ceui.pixiv.shaftapi.TranslateResult
import ceui.pixiv.shaftapi.TranslateRequest
import ceui.pixiv.shaftapi.translateTexts
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

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
    fun `云翻译把流式上游状态传给页面而不混入译文`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "event: phase\ndata: {\"phase\":\"thinking\",\"reasoning_content\":\"推理已转发上游端点\"}\n\n" +
                "event: phase\ndata: {\"phase\":\"generating\"}\n\n" +
                "event: result\ndata: {\"translations\":[\"你好\"]}\n\n",
        ))
        val phases = CopyOnWriteArrayList<AiTranslatePhase>()
        val result = CloudTranslator.translateBatchWith(api, 7, listOf("hello"), "zh", onPhase = { phases.add(it) })
        assertEquals(listOf("你好"), result)
        assertEquals(listOf(AiTranslatePhase.Thinking("推理已转发上游端点"), AiTranslatePhase.Generating), phases)
    }

    @Test
    fun `分片乱序完成且部分失败时重复项仍回填原位置`() = runBlocking {
        val a = "a".repeat(1600)
        val b = "b".repeat(1600)
        val c = "c".repeat(1600)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val text = Gson().fromJson(request.body.readUtf8(), TranslateRequest::class.java).texts.single()
                return when (text) {
                    a -> json(200, """{"translations":["A"]}""").setBodyDelay(100, TimeUnit.MILLISECONDS)
                    b -> json(502, """{"error":"upstream_failed"}""")
                    c -> json(200, """{"translations":["C"]}""")
                    else -> json(400, "{}")
                }
            }
        }
        val items = mutableListOf<Pair<Int, String>>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = CloudTranslator.translateBatchWith(api, 7L, listOf(a, b, c, a, " "), "en",
            onItem = { index, text -> items.add(index to text) },
            onProgress = { done, total -> progress.add(done to total) })
        assertEquals(listOf("A", "", "C", "A", " "), result)
        assertEquals(listOf(0 to "A", 3 to "A", 2 to "C"), items)
        assertEquals(listOf(3 to 5, 4 to 5, 5 to 5), progress)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `空白和重复文本不消耗重复请求，译文恢复原位置`() = runBlocking {
        server.enqueue(json(200, """{"translations":["你好","谢谢"]}"""))
        val items = mutableListOf<Pair<Int, String>>()
        var progress = 0 to 0
        val out = CloudTranslator.translateBatchWith(
            api, 7L, listOf("こんにちは", " ", "ありがとう", "こんにちは", ""), "zh",
            onItem = { i, text -> items.add(i to text) },
            onProgress = { done, total -> progress = done to total },
        )
        assertEquals(listOf("你好", " ", "谢谢", "你好", ""), out)
        assertEquals(listOf(0 to "你好", 3 to "你好", 2 to "谢谢"), items)
        assertEquals(5 to 5, progress)
        assertEquals("""{"uid":7,"texts":["こんにちは","ありがとう"],"lang":"zh-CN"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `全空白文本直接返回，不发送请求`() = runBlocking {
        val inputs = listOf("", " \n")
        var progress = 0 to 0
        assertEquals(inputs, CloudTranslator.translateBatchWith(api, 7L, inputs, "en",
            onProgress = { done, total -> progress = done to total }))
        assertEquals(2 to 2, progress)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `解析实际引擎并兼容旧服务端缺失字段`() = runBlocking {
        server.enqueue(json(200, """{"translations":["你好"],"engine":{"vendor":"Tencent","model":"Transmart"}}"""))
        val result = api.translateTexts(7L, listOf("hello"), "zh-CN") as TranslateResult.Success
        assertEquals("Tencent · Transmart", result.engine?.display)
        server.enqueue(json(200, """{"translations":["你好"]}"""))
        assertEquals(null, (api.translateTexts(7L, listOf("hello"), "zh-CN") as TranslateResult.Success).engine)
        assertEquals("Tencent · Transmart → OpenAI · gpt-test", CloudTranslateEngine(
            "Tencent", "Transmart", CloudTranslateEngine("OpenAI", "gpt-test"),
        ).display)
    }

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
        assertEquals(AiTranslatePhase.Generating, phase)

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
    fun `503 translate_disabled 触发 onServerDisabled 回调`() = runBlocking {
        server.enqueue(json(503, """{"error":"translate_disabled"}"""))
        var disabled = 0
        try {
            CloudTranslator.translateBatchWith(api, 7L, listOf("a"), "en", onServerDisabled = { disabled++ })
            fail("expected CloudTranslateException")
        } catch (e: CloudTranslateException) {
            assertEquals(503, e.code)
        }
        assertEquals(1, disabled)
    }

    @Test
    fun `服务端关停时这一批直接交给下一级引擎重做，不抛错`() = runBlocking {
        server.enqueue(json(503, """{"error":"translate_disabled"}"""))
        val fallback = object : Translator {
            val calls = mutableListOf<List<String>>()
            override suspend fun translate(input: String, outputLang: String, onPhase: ((AiTranslatePhase) -> Unit)?): String =
                "G:$input"
            override suspend fun translateBatch(
                inputs: List<String>, outputLang: String,
                onItem: ((Int, String) -> Unit)?, onProgress: ((Int, Int) -> Unit)?,
                onPhase: ((AiTranslatePhase) -> Unit)?, onRequestSent: (() -> Unit)?,
            ): List<String> {
                calls.add(inputs)
                return inputs.map { "G:$it" }
            }
        }
        var disabled = 0
        val out = CloudTranslator.translateBatchWith(
            api, 7L, listOf("a", "b"), "en",
            onServerDisabled = { disabled++ }, fallback = fallback,
        )
        assertEquals(listOf("G:a", "G:b"), out)
        assertEquals(listOf(listOf("a", "b")), fallback.calls)
        assertEquals(1, disabled)
    }

    @Test
    fun `分片同时受字符数和条数约束`() {
        // 100 条 10 字符：字符远不到 3000，但条数要按 64 切成两段
        val many = List(100) { "0123456789" }
        assertEquals(listOf(0 to 64, 64 to 100), CloudTranslator.chunkRanges(many, 3000, 64))
        // 字符先切：每条 1500 加分隔符，两条就超 3000，只能一条一段
        val long = List(5) { "x".repeat(1500) }
        assertEquals(listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5), CloudTranslator.chunkRanges(long, 3000, 64))
        assertEquals(listOf(0 to 2, 2 to 3), CloudTranslator.chunkRanges(listOf("a", "b", "c"), 3000, 2))
    }

    @Test
    fun `译文里的 JSON null 变成空串而不是 NPE`() = runBlocking {
        server.enqueue(json(200, """{"translations":["a",null],"quotas":[]}"""))
        val out = CloudTranslator.translateBatchWith(api, 7L, listOf("x", "y"), "en")
        assertEquals(listOf("a", ""), out)
    }

    @Test
    fun `gtx 语言码映射成服务端白名单`() {
        assertEquals("zh-CN", CloudTranslator.serverLangOf("zh"))
        assertEquals("zh-CN", CloudTranslator.serverLangOf("zh-CN"))
        assertEquals("zh-TW", CloudTranslator.serverLangOf("zh-TW"))
        assertEquals("ja", CloudTranslator.serverLangOf("ja"))
    }
}
