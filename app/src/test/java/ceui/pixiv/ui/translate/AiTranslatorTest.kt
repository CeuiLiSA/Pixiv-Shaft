package ceui.pixiv.ui.translate

import ceui.lisa.R
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [AiTranslator] 的纯函数与 HTTP 协议层测试(#975)。
 *
 * HTTP 部分走 MockWebServer + [AiTranslator.testConfig](该入口所有配置显式传参,
 * 不碰 Shaft.sSettings / Android 运行时;prompt 传非空避免走到 AppLocales;
 * forceStreaming=true 可强制走 SSE 通道覆盖流式协议层)。
 */
class AiTranslatorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/v1").toString()

    private fun completionJson(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":${org.json.JSONObject.quote(content)}}}]}"""

    /** 组装标准 SSE 响应:每个事件一条 data:,空行分隔,事件流结束。 */
    private fun sse(vararg events: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(events.joinToString("\n\n") { "data: $it" } + "\n\n")

    // ---------- normalizeEndpoint ----------

    @Test
    fun `base URL 自动补 chat completions 路径`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            AiTranslator.normalizeEndpoint("https://api.openai.com/v1")
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            AiTranslator.normalizeEndpoint("https://api.openai.com/v1/")
        )
    }

    @Test
    fun `用户直接贴完整端点不重复拼接`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            AiTranslator.normalizeEndpoint("https://api.openai.com/v1/chat/completions")
        )
    }

    @Test
    fun `models 端点容忍完整 chat completions 地址`() {
        assertEquals(
            "http://192.168.1.5:11434/v1/models",
            AiTranslator.normalizeModelsEndpoint("http://192.168.1.5:11434/v1/chat/completions")
        )
        assertEquals(
            "http://192.168.1.5:11434/v1/models",
            AiTranslator.normalizeModelsEndpoint("http://192.168.1.5:11434/v1/")
        )
    }

    // ---------- parseJsonArrayReply ----------

    @Test
    fun `裸 JSON 数组直接解析`() {
        assertEquals(listOf("a", "b"), AiTranslator.parseJsonArrayReply("""["a","b"]"""))
    }

    @Test
    fun `markdown 围栏包裹的数组也认`() {
        assertEquals(
            listOf("你好", "世界"),
            AiTranslator.parseJsonArrayReply("```json\n[\"你好\",\"世界\"]\n```")
        )
        assertEquals(
            listOf("你好"),
            AiTranslator.parseJsonArrayReply("```\n[\"你好\"]\n```")
        )
    }

    @Test
    fun `解析不了返回 null 走逐条兜底`() {
        assertNull(AiTranslator.parseJsonArrayReply("好的，以下是翻译结果：..."))
        assertNull(AiTranslator.parseJsonArrayReply("""{"a":1}"""))
    }

    // ---------- chunkByCharLimit ----------

    @Test
    fun `总量不超限时只有一个 chunk`() {
        assertEquals(
            listOf(0 to 3),
            AiTranslator.chunkByCharLimit(listOf("aa", "bb", "cc"), 100)
        )
    }

    @Test
    fun `超限时按累计字符数切段且不丢元素`() {
        val chunks = AiTranslator.chunkByCharLimit(listOf("aaaa", "bbbb", "cccc"), 10)
        assertEquals(0, chunks.first().first)
        assertEquals(3, chunks.last().second)
        assertTrue(chunks.size > 1)
        // 区间首尾相接
        for (i in 1 until chunks.size) {
            assertEquals(chunks[i - 1].second, chunks[i].first)
        }
    }

    @Test
    fun `单条超限自己占一段`() {
        assertEquals(
            listOf(0 to 1, 1 to 2),
            AiTranslator.chunkByCharLimit(listOf("a".repeat(50), "b"), 10)
        )
    }

    // ---------- apiErrorMessage ----------

    @Test
    fun `OpenAI 标准错误体抽出 message`() {
        val body = """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""
        assertEquals("HTTP 401: Incorrect API key provided", AiTranslator.apiErrorMessage(401, body))
    }

    @Test
    fun `非标准错误体退回截断原文`() {
        assertEquals("HTTP 502: Bad Gateway", AiTranslator.apiErrorMessage(502, "Bad Gateway"))
    }

    // ---------- HTTP 协议层(经 testConfig,全显式配置) ----------

    @Test
    fun `成功响应返回译文并带 Bearer 头`() = runBlocking {
        server.enqueue(MockResponse().setBody(completionJson("你好，世界！")))
        val out = AiTranslator.testConfig(baseUrl(), "sk-test", "test-model", "translate to zh")
        assertEquals("你好，世界！", out)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val sent = org.json.JSONObject(recorded.body.readUtf8())
        assertEquals("test-model", sent.getString("model"))
        // 兼容推理系模型:不携带 temperature
        assertTrue(!sent.has("temperature"))
    }

    @Test
    fun `key 为空时不携带 Authorization 头`() = runBlocking {
        server.enqueue(MockResponse().setBody(completionJson("hi")))
        AiTranslator.testConfig(baseUrl(), "", "m", "p")
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `5xx 重试一次后成功`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"boom"}}"""))
        server.enqueue(MockResponse().setBody(completionJson("ok")))
        val out = AiTranslator.testConfig(baseUrl(), "k", "m", "p")
        assertEquals("ok", out)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `4xx 配置错误不重试且报人话`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Incorrect API key provided"}}""")
        )
        try {
            AiTranslator.testConfig(baseUrl(), "bad", "m", "p")
            fail("should throw")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Incorrect API key provided"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `fetchModels 解析 data 数组的 id`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"object":"list","data":[{"id":"gpt-4o-mini","object":"model"},{"id":"gpt-4o","object":"model"}]}"""
            )
        )
        val models = AiTranslator.fetchModels(baseUrl(), "sk-test")
        assertEquals(listOf("gpt-4o-mini", "gpt-4o"), models)
        assertEquals("/v1/models", server.takeRequest().path)
    }

    // ---------- 流式(SSE)协议层(forceStreaming=true,不碰 Settings) ----------

    @Test
    fun `流式成功按 SSE 解析并回调思考生成阶段`() = runBlocking {
        val phases = CopyOnWriteArrayList<AiTranslatePhase>()
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"reasoning_content":"让我想想"}}]}""",
                """{"choices":[{"delta":{"content":"你好，世界！"}}]}""",
                "[DONE]",
            )
        )
        val out = AiTranslator.testConfig(
            baseUrl(), "sk-test", "test-model", "translate to zh",
            forceStreaming = true,
            onPhase = { phases.add(it) },
        )
        assertEquals("你好，世界！", out)
        assertEquals(listOf(AiTranslatePhase.Thinking("让我想想"), AiTranslatePhase.Generating), phases.toList())

        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val sent = org.json.JSONObject(recorded.body.readUtf8())
        assertEquals(true, sent.getBoolean("stream"))
        assertTrue(sent.has("stream_options"))
    }

    @Test
    fun `上游提示分片合并且心跳不进入译文`() = runBlocking {
        val phases = CopyOnWriteArrayList<AiTranslatePhase>()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"推理已转发\"}}]}\n\n" +
                ": keep-alive\n\n" +
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"上游端点\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"晚到的提示\"}}]}\n\n" +
                "data: [DONE]\n\n"
        ))
        val out = AiTranslator.testConfig(baseUrl(), "", "m", "p", forceStreaming = true, onPhase = { phases.add(it) })
        assertEquals("你好", out)
        assertEquals(listOf(
            AiTranslatePhase.Thinking("推理已转发"),
            AiTranslatePhase.Thinking("推理已转发上游端点"),
            AiTranslatePhase.Generating,
        ), phases.toList())
    }

    @Test
    fun `逐字上游提示只弹一次完整阶段提示`() = runBlocking {
        val notices = CopyOnWriteArrayList<Int>()
        server.enqueue(sse(
            """{"choices":[{"delta":{"reasoning_content":"推"}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"理已转发上游端点"}}]}""",
            """{"choices":[{"delta":{"content":"你好"}}]}""", "[DONE]",
        ))
        val out = AiTranslator.testConfig(
            baseUrl(), "", "m", "p", forceStreaming = true,
            onPhase = onceThinkingPhase { notices.add(it) },
        )
        assertEquals("你好", out)
        assertEquals(listOf(R.string.ai_translate_thinking), notices.toList())
    }

    @Test
    fun `长推理仅保留最近提示且不会截断 emoji`() = runBlocking {
        val phases = CopyOnWriteArrayList<AiTranslatePhase>()
        val reasoning = "早".repeat(300) + "😀" + "新".repeat(239)
        server.enqueue(sse(
            """{"choices":[{"delta":{"reasoning_content":${org.json.JSONObject.quote(reasoning)}}}]}""",
            """{"choices":[{"delta":{"content":"你好"}}]}""", "[DONE]",
        ))
        assertEquals("你好", AiTranslator.testConfig(baseUrl(), "", "m", "p", forceStreaming = true, onPhase = { phases.add(it) }))
        assertEquals(AiTranslatePhase.Thinking("新".repeat(239)), phases.first())
    }

    @Test
    fun `批量阶段转发更新去重且出译文后不倒退`() {
        val phases = mutableListOf<AiTranslatePhase>()
        val aggregator = AiTranslator.PhaseAggregator { phases.add(it) }
        aggregator.report(AiTranslatePhase.Thinking("已转发"))
        aggregator.report(AiTranslatePhase.Thinking("已转发"))
        aggregator.report(AiTranslatePhase.Thinking("等待上游"))
        aggregator.report(AiTranslatePhase.Generating)
        aggregator.report(AiTranslatePhase.Thinking("另一分片仍在等待"))
        aggregator.report(AiTranslatePhase.Generating)
        assertEquals(listOf(
            AiTranslatePhase.Thinking("已转发"),
            AiTranslatePhase.Thinking("等待上游"),
            AiTranslatePhase.Generating,
        ), phases)
    }

    @Test
    fun `流式 delta 中 content 为 null 不拼入 null 字符串`() = runBlocking {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"reasoning_content":"想","content":null}}]}""",
                """{"choices":[{"delta":{"reasoning_content":null,"content":"你"}}]}""",
                """{"choices":[{"delta":{"content":"好"}}]}""",
                "[DONE]",
            )
        )
        val out = AiTranslator.testConfig(baseUrl(), "", "m", "p", forceStreaming = true)
        assertEquals("你好", out)
    }

    @Test
    fun `流式 DONE 后主动断开仍正常收尾`() = runBlocking {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"ok"}}]}""", "[DONE]"))
        val out = AiTranslator.testConfig(baseUrl(), "", "m", "p", forceStreaming = true)
        assertEquals("ok", out)
    }

    @Test
    fun `流式只思考没内容降级非流式后成功`() = runBlocking {
        server.enqueue(sse("""{"choices":[{"delta":{"reasoning_content":"想半天"}}]}""", "[DONE]"))
        server.enqueue(MockResponse().setBody(completionJson("你好，世界！")))
        val out = AiTranslator.testConfig(baseUrl(), "", "m", "p", forceStreaming = true)
        assertEquals("你好，世界！", out)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `非流式空响应重试一次后报 empty completion`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        try {
            AiTranslator.testConfig(baseUrl(), "", "m", "p")
            fail("should throw")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("empty completion"))
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `流式遇到非 SSE 响应自动降级非流式`() = runBlocking {
        server.enqueue(MockResponse().setBody(completionJson("你好，世界！")))
        server.enqueue(MockResponse().setBody(completionJson("你好，世界！")))
        val out = AiTranslator.testConfig(baseUrl(), "", "m", "p", forceStreaming = true)
        assertEquals("你好，世界！", out)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `流式 400 stream_options 不认时去掉重试成功`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"stream_options not supported"}}""")
        )
        server.enqueue(sse("""{"choices":[{"delta":{"content":"你好，世界！"}}]}""", "[DONE]"))
        val out = AiTranslator.testConfig(baseUrl(), "k", "m", "p", forceStreaming = true)
        assertEquals("你好，世界！", out)
        assertEquals(2, server.requestCount)

        val first = org.json.JSONObject(server.takeRequest().body.readUtf8())
        val retried = org.json.JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(true, first.getBoolean("stream"))
        assertTrue(first.has("stream_options"))
        assertEquals(true, retried.getBoolean("stream"))
        assertTrue(!retried.has("stream_options"))
    }

    @Test
    fun `流式 400 重试仍失败则降级非流式`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"no stream"}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"no stream"}}"""))
        server.enqueue(MockResponse().setBody(completionJson("你好，世界！")))
        val out = AiTranslator.testConfig(baseUrl(), "k", "m", "p", forceStreaming = true)
        assertEquals("你好，世界！", out)
        assertEquals(3, server.requestCount)

        repeat(2) { server.takeRequest() }
        val fallback = org.json.JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(false, fallback.getBoolean("stream"))
    }

    @Test
    fun `流式 401 配置错误不重试不降级`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":{"message":"Incorrect API key provided"}}""")
        )
        try {
            AiTranslator.testConfig(baseUrl(), "bad", "m", "p", forceStreaming = true)
            fail("should throw")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Incorrect API key provided"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `流式 5xx 降级非流式后成功`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"message":"boom"}}"""))
        server.enqueue(MockResponse().setBody(completionJson("ok")))
        val out = AiTranslator.testConfig(baseUrl(), "k", "m", "p", forceStreaming = true)
        assertEquals("ok", out)
        assertEquals(2, server.requestCount)
    }

    // ---------- 指令遵循校验 ----------

    @Test
    fun `测试翻译输出包含原文判未遵循指令`() = runBlocking {
        server.enqueue(MockResponse().setBody(completionJson("「こんにちは、世界！」")))
        try {
            AiTranslator.testConfig(baseUrl(), "k", "m", "translate to zh")
            fail("should throw")
        } catch (e: Exception) {
            assertTrue(e is java.io.IOException)
            assertTrue(e.message!!.contains("输出了原文"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `JSON 数组里的 null 元素解析为空串`() {
        assertEquals(listOf("a", "", "c"), AiTranslator.parseJsonArrayReply("""["a",null,"c"]"""))
    }
}
