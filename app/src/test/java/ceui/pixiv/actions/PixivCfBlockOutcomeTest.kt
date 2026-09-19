package ceui.pixiv.actions

import ceui.lisa.core.JavaAsync
import ceui.lisa.http.CfBlockHttpChainTest
import ceui.pixiv.actionqueue.ActionOutcome
import ceui.pixiv.actionqueue.RetryScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PixivCfBlockOutcomeTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    @Before
    fun setUp() {
        // 队列结局测试不运行异步 UI 提示。
        Dispatchers.setMain(StandardTestDispatcher())
        server.start()
    }

    @After
    fun tearDown() {
        JavaAsync.appScope.coroutineContext.cancelChildren()
        Dispatchers.resetMain()
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `代理转发 CF 拦截页时收藏动作直接失败`() {
        val error = httpError(MockResponse().setResponseCode(403)
            .setHeader("Content-Type", "text/html")
            .setHeader("Server", "nginx")
            .setBody(CfBlockHttpChainTest.CF_PAGE_BODY))

        assertTrue(error.toActionOutcome(isOnline = true) is ActionOutcome.Fail)
        assertEquals(CfBlockHttpChainTest.CF_PAGE_BODY, error.response()!!.errorBody()!!.string())
    }

    @Test
    fun `源站业务 403 保持单动作重试`() {
        val error = httpError(MockResponse().setResponseCode(403)
            .setHeader("Content-Type", "application/json")
            .setHeader("x-envoy-upstream-service-time", "10")
            .setBody(CfBlockHttpChainTest.ORIGIN_403_BODY))

        val outcome = error.toActionOutcome(isOnline = true)
        assertTrue(outcome is ActionOutcome.Retry)
        assertEquals(RetryScope.ACTION, (outcome as ActionOutcome.Retry).scope)
    }

    private fun httpError(response: MockResponse): HttpException {
        server.enqueue(response)
        val api = Retrofit.Builder().baseUrl(server.url("/"))
            .client(client).build().create(CfBlockHttpChainTest.ProbeApi::class.java)
        val error = runBlocking { runCatching { api.detail() } }.exceptionOrNull()
        return error as? HttpException ?: throw AssertionError("Expected HTTP error", error)
    }
}
