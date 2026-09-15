package ceui.pixiv.shaftapi

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MediaPerformanceTest {
    @Test
    fun `fast transfer emits initial and final progress instead of a callback per chunk`() {
        val updates = mutableListOf<Long>()
        val progress = MediaUploadProgress()
        val total = 25L * 1024 * 1024
        var sent = 0L
        while (sent < total) {
            sent = minOf(total, sent + 8192)
            if (progress.shouldReport(sent, total, sent)) updates += sent
        }
        assertEquals(2, updates.size)
        assertEquals(total, updates.last())
    }

    @Test
    fun `slow transfer remains responsive and always reports final bytes`() {
        val progress = MediaUploadProgress()
        val updates = (1L..200).filter { progress.shouldReport(it, 200, it * 10_000_000) }
        assertTrue(updates.size <= 22)
        assertEquals(200L, updates.last())
        assertFalse(progress.shouldReport(200, 200, 3_000_000_000))
    }

    @Test
    fun `Retrofit timing tag stays local and complete still returns preview in one response`() =
        runBlocking {
            MockWebServer().use { server ->
                val trace = MediaUploadTrace("test")
                var tagged = false
                val client =
                    OkHttpClient.Builder()
                        .eventListenerFactory { call ->
                            tagged = call.request().tag(MediaUploadTrace::class.java) === trace
                            MediaNetworkTiming.factory.create(call)
                        }
                        .build()
                val api =
                    Retrofit.Builder()
                        .baseUrl(server.url("/"))
                        .addConverterFactory(GsonConverterFactory.create())
                        .client(client)
                        .build()
                        .create(MediaApi::class.java)
                server.enqueue(
                    MockResponse()
                        .setHeader("Server-Timing", "sign;dur=0.20, total;dur=2.10")
                        .setBody(
                            """{"mediaId":"test","objectKey":"image.jpg","contentType":"image/jpeg","size":12,"width":4,"height":3,"createdAt":1,"url":"https://media.pixshaft.com/image.jpg?q-signature=test","expiresAt":1700000300000}"""
                        )
                )
                val response =
                    api.completeUpload(
                        MediaUploadCompleteRequest(
                            "test",
                            "image.jpg",
                            "image/jpeg",
                            12,
                            width = 4,
                            height = 3,
                        ),
                        trace,
                    )
                assertTrue(tagged)
                assertTrue(response.url.contains("q-signature=test"))
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/v1/media/upload/complete", request.path)
                assertFalse(request.body.readUtf8().contains("trace"))
                assertEquals(1, server.requestCount)
                client.connectionPool.evictAll()
            }
        }

    @Test
    fun `COS warmup reuses its connection without modifying the following signed PUT`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "test"))
            val client = OkHttpClient()
            val running = AtomicBoolean()
            MediaHttpTransport.warm(client, server.url("/").toString(), running)
            val warm = server.takeRequest(2, TimeUnit.SECONDS)!!
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (running.get() && System.nanoTime() < deadline) Thread.sleep(5)
            assertFalse(running.get())
            // Re-opening the picker must not issue another HEAD when a connection is idle.
            MediaHttpTransport.warm(client, server.url("/").toString(), running)
            val signed = server.url("/image.jpg?q-signature=preserve-me")
            client
                .newBuilder()
                .build()
                .newCall(
                    Request.Builder()
                        .url(signed)
                        .header("x-cos-forbid-overwrite", "true")
                        .put("exact bytes".toRequestBody())
                        .build()
                )
                .execute()
                .use { assertEquals(200, it.code) }
            val upload = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("HEAD", warm.method)
            assertNull(warm.getHeader("Authorization"))
            assertEquals(1, upload.sequenceNumber)
            assertEquals("/image.jpg?q-signature=preserve-me", upload.path)
            assertEquals("true", upload.getHeader("x-cos-forbid-overwrite"))
            assertEquals("exact bytes", upload.body.readUtf8())
            assertEquals(2, server.requestCount)
            client.connectionPool.evictAll()
        }
    }
}
