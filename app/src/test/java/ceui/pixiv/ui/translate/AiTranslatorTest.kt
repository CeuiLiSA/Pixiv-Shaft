package ceui.pixiv.ui.translate

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

/**
 * [AiTranslator] 的纯函数与 HTTP 协议层测试(#975)。
 *
 * HTTP 部分走 MockWebServer + [AiTranslator.testConfig](该入口所有配置显式传参,
 * 不碰 Shaft.sSettings / Android 运行时;prompt 传非空避免走到 AppLocales)。
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
}
