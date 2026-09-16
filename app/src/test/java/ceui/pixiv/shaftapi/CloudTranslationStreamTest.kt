package ceui.pixiv.shaftapi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class CloudTranslationStreamTest {
    private fun api(server: MockWebServer) = Retrofit.Builder().baseUrl(server.url("/"))
        // Android's normal callback executor must be bypassed for blocking stream reads.
        .callbackExecutor { error("stream callback must not run on the UI executor") }
        .addConverterFactory(GsonConverterFactory.create()).build().create(PixshaftApi::class.java)

    @Test fun `reasoning arrives before result and never enters translated text`() = runBlocking {
        MockWebServer().use { server ->
            val first = "event: phase\ndata: {\"phase\":\"thinking\",\"reasoning_content\":\"推理已转发上游端点\"}\n\n"
            val rest = "event: delta\ndata: {\"content\":\"[\"}\n\n" + ": keep-alive\n\nevent: phase\ndata: {\"phase\":\"generating\"}\n\n" +
                "event: result\ndata: {\"translations\":[\"你好\"],\"quotas\":[]}\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(first + rest).throttleBody(first.toByteArray().size.toLong(), 300, TimeUnit.MILLISECONDS))
            val thinking = CompletableDeferred<String>()
            var generations = 0
            val result = async { api(server).translateTextsStreaming(7, listOf("hello"), "zh-CN",
                { thinking.complete(it) }, { generations++ }) }
            assertEquals("推理已转发上游端点", withTimeout(3000) { thinking.await() })
            assertFalse(result.isCompleted)
            assertEquals(listOf("你好"), (withTimeout(3000) { result.await() } as TranslateResult.Success).translations)
            assertEquals(1, generations)
            val request = server.takeRequest()
            assertEquals("text/event-stream", request.getHeader("Accept"))
            assertEquals(if (ceui.lisa.BuildConfig.DEBUG) "1" else null, request.getHeader("X-Shaft-Translate-Trace"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `truncated stream fails and JSON rollback does not replay request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(": keep-alive\n\n"))
            assertTrue(api(server).translateTextsStreaming(7, listOf("hello"), "zh-CN", {}, {}) is TranslateResult.NetworkFailure)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"translations\":[\"你好\"]}"))
            assertEquals(listOf("你好"), (api(server).translateTextsStreaming(7, listOf("hello"), "zh-CN", {}, {}) as TranslateResult.Success).translations)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `stream failure preserves server error and never reports success`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("event: error\ndata: {\"status\":502,\"error\":\"upstream_failed\"}\n\n"))
            assertEquals(TranslateResult.HttpFailure(502, "upstream_failed"),
                api(server).translateTextsStreaming(7, listOf("hello"), "zh-CN", {}, {}))
        }
    }

    @Test fun `cancellation interrupts a waiting response body`() = runBlocking {
        MockWebServer().use { server ->
            val first = "event: phase\ndata: {\"phase\":\"thinking\",\"reasoning_content\":\"waiting\"}\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(first + ": keep-alive\n\n").throttleBody(first.toByteArray().size.toLong(), 2, TimeUnit.SECONDS))
            val thinking = CompletableDeferred<Unit>()
            val job = async { api(server).translateTextsStreaming(7, listOf("hello"), "zh-CN", { thinking.complete(Unit) }, {}) }
            withTimeout(3000) { thinking.await() }
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }
}
