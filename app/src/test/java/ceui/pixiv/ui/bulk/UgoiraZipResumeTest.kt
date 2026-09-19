package ceui.pixiv.ui.bulk

import ceui.lisa.utils.Params
import ceui.pixiv.download.StageStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 直接调用生产下载函数，以真实 HTTP 和文件字节验证中断后续传，不复制下载实现。 */
class UgoiraZipResumeTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var target: File
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val payload = ByteArray(128 * 1024) { (it % 251).toByte() }
    private val etag = "\"ugoira-v1\""
    private val url get() = server.url("/ugoira.zip").toString()
    private val part get() = File(target.path + ".part")
    private val meta get() = File(target.path + ".part.meta")

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        target = File(folder.root, "ugoira.zip")
    }

    @After fun tearDown() {
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun full(bytes: ByteArray = payload) = MockResponse()
        .setHeader("ETag", etag)
        .setBody(Buffer().write(bytes))

    private fun tail(offset: Long) = MockResponse()
        .setResponseCode(206)
        .setHeader("ETag", etag)
        .setHeader("Content-Range", "bytes $offset-${payload.lastIndex}/${payload.size}")
        .setBody(Buffer().write(payload, offset.toInt(), payload.size - offset.toInt()))

    private fun seed(size: Int = 4096, source: String = url, validator: String? = etag) {
        target.delete()
        part.writeBytes(payload.copyOf(size))
        StageStore.writeManifest(meta, StageStore.buildManifest(source, validator, null, payload.size.toLong()))
    }

    private fun assertComplete(bytes: ByteArray = payload) {
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(part.exists())
        assertFalse(meta.exists())
    }

    @Test fun `fresh download keeps required headers and commits complete bytes`() = runBlocking {
        server.enqueue(full())
        downloadZipTo(url, target, client)
        assertComplete()
        val request = server.takeRequest()
        assertNull(request.getHeader("Range"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertEquals(Params.IMAGE_REFERER, request.getHeader("Referer"))
        assertEquals(Params.PHONE_MODEL, request.getHeader("User-Agent"))
    }

    @Test fun `disconnected transfer resumes real saved bytes and reports total progress`() = runBlocking {
        server.enqueue(full().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try {
            downloadZipTo(url, target, client)
            fail("disconnected response must fail")
        } catch (_: IOException) {
            // 部分字节和 validator 必须留给下一次调用。
        }
        val offset = part.length()
        assertTrue(offset in 1 until payload.size.toLong())
        assertFalse(target.exists())
        assertEquals(etag, StageStore.readManifest(meta)?.validator)
        server.takeRequest()

        server.enqueue(tail(offset))
        val progress = mutableListOf<Int>()
        downloadZipTo(url, target, client) { progress += it }
        assertComplete()
        val request = server.takeRequest()
        assertEquals("bytes=$offset-", request.getHeader("Range"))
        assertEquals(etag, request.getHeader("If-Range"))
        assertEquals((offset * 100 / payload.size).toInt(), progress.first())
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test fun `page cancellation keeps partial data for a later invocation`() = runBlocking {
        server.enqueue(full())
        val pageJob = Job()
        try {
            withContext(pageJob) {
                downloadZipTo(url, target, client) { if (it in 1..99) pageJob.cancel() }
            }
            fail("page cancellation must propagate")
        } catch (_: CancellationException) {
            // 模拟引擎取消，无需在测试中等 12 秒。
        }
        val offset = part.length()
        assertTrue(offset in 1 until payload.size.toLong())
        assertFalse(target.exists())
        server.takeRequest()
        server.enqueue(tail(offset))
        downloadZipTo(url, target, client)
        assertComplete()
        assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
    }

    @Test fun `server ignoring Range replaces partial instead of appending`() = runBlocking {
        seed()
        server.enqueue(full())
        downloadZipTo(url, target, client)
        assertComplete()
        assertEquals("bytes=4096-", server.takeRequest().getHeader("Range"))
    }

    @Test fun `changed representation returning 200 replaces both bytes and metadata`() = runBlocking {
        seed()
        val changed = ByteArray(32 * 1024) { 42 }
        server.enqueue(full(changed).setHeader("ETag", "\"v2\""))
        downloadZipTo(url, target, client)
        assertComplete(changed)
        assertEquals(etag, server.takeRequest().getHeader("If-Range"))
    }

    @Test fun `invalid partial responses fall back once without Range`() = runBlocking {
        val invalid = listOf(
            null,
            "bytes 4000-${payload.lastIndex}/${payload.size}", // 起点错误
            "bytes 4096-${payload.size}/${payload.size}", // end 越界
            "bytes 4096-8191/${payload.size}", // 没有返回剩余全部字节
            "bytes 4096-${payload.lastIndex}/*", // 无法确认完整大小
            "bytes */${payload.size}",
        )
        for (header in invalid) {
            seed()
            val response = tail(4096)
            if (header == null) response.removeHeader("Content-Range")
            else response.setHeader("Content-Range", header)
            server.enqueue(response)
            server.enqueue(full())
            downloadZipTo(url, target, client)
            assertComplete()
            assertEquals("bytes=4096-", server.takeRequest().getHeader("Range"))
            val fallback = server.takeRequest()
            assertNull(fallback.getHeader("Range"))
            assertNull(fallback.getHeader("If-Range"))
        }
    }

    @Test fun `206 length and validator conflicts never get appended`() = runBlocking {
        for (response in listOf(
            tail(4096).setHeader("ETag", "\"changed\""),
            tail(4096).setBody("too short"),
            tail(4096).setHeader("Content-Range", "bytes 4096-${payload.size}/${payload.size + 1}"),
        )) {
            seed()
            server.enqueue(response)
            server.enqueue(full())
            downloadZipTo(url, target, client)
            assertComplete()
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun `repeated invalid ranges stop after one fallback and never commit`() = runBlocking {
        seed()
        repeat(2) { server.enqueue(tail(4096).removeHeader("Content-Range")) }
        try {
            downloadZipTo(url, target, client)
            fail("invalid ranges must fail")
        } catch (_: IOException) {
            assertFalse(target.exists())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `416 commits only complete bytes with a matching validator`() = runBlocking {
        seed(payload.size)
        server.enqueue(MockResponse().setResponseCode(416)
            .setHeader("Content-Range", "bytes */${payload.size}").setHeader("ETag", etag))
        downloadZipTo(url, target, client)
        assertComplete()
        assertEquals(1, server.requestCount)
    }

    @Test fun `416 with an incomplete file or unconfirmed representation restarts`() = runBlocking {
        for ((size, validator) in listOf(4096 to etag, payload.size to "\"changed\"", payload.size to null)) {
            seed(size)
            val response = MockResponse().setResponseCode(416)
                .setHeader("Content-Range", "bytes */${payload.size}")
            if (validator != null) response.setHeader("ETag", validator)
            server.enqueue(response)
            server.enqueue(full())
            downloadZipTo(url, target, client)
            assertComplete()
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun `partial removed while requesting a range is downloaded fresh`() = runBlocking {
        for (status in listOf(206, 416)) {
            seed(if (status == 416) payload.size else 4096)
            val clearingClient = client.newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request()).also {
                    if (chain.request().header("Range") != null) part.delete()
                }
            }.build()
            server.enqueue(if (status == 206) tail(4096) else MockResponse().setResponseCode(416)
                .setHeader("Content-Range", "bytes */${payload.size}").setHeader("ETag", etag))
            server.enqueue(full())
            downloadZipTo(url, target, clearingClient)
            assertComplete()
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun `legacy or untrusted partials restart without Range`() = runBlocking {
        for (case in 0..4) {
            seed()
            when (case) {
                0 -> meta.delete()
                1 -> seed(source = server.url("/different.zip").toString())
                2 -> seed(validator = "W/$etag")
                3 -> seed(validator = null)
                4 -> meta.writeText("not json")
            }
            server.enqueue(full())
            downloadZipTo(url, target, client)
            assertComplete()
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun `date validator is used only without ETag and with sufficient time separation`() = runBlocking {
        val modified = "Wed, 01 Jan 2020 00:00:00 GMT"
        for (case in 0..2) {
            target.delete()
            val response = full().removeHeader("ETag")
                .setHeader("Last-Modified", modified)
                .setHeader("Date", "Wed, 01 Jan 2020 00:02:00 GMT")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            if (case == 1) response.setHeader("ETag", "W/$etag")
            if (case == 2) response.setHeader("Date", modified)
            server.enqueue(response)
            try {
                downloadZipTo(url, target, client)
                fail("disconnected response must fail")
            } catch (_: IOException) { }
            server.takeRequest()
            if (case == 0) {
                assertEquals(modified, StageStore.readManifest(meta)?.validator)
                server.enqueue(tail(part.length()).removeHeader("ETag").setHeader("Last-Modified", modified))
            } else {
                assertNull(StageStore.readManifest(meta)?.validator)
                server.enqueue(full())
            }
            downloadZipTo(url, target, client)
            assertComplete()
            assertEquals(if (case == 0) modified else null, server.takeRequest().getHeader("If-Range"))
        }
    }

    @Test fun `206 without validator retains original metadata across another cancellation`() = runBlocking {
        seed()
        server.enqueue(tail(4096).removeHeader("ETag"))
        val job = Job()
        try {
            withContext(job) {
                downloadZipTo(url, target, client) { if (it >= 10) job.cancel() }
            }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(etag, StageStore.readManifest(meta)?.validator)
        server.takeRequest()
        server.enqueue(tail(part.length()))
        downloadZipTo(url, target, client)
        assertComplete()
        assertEquals(etag, server.takeRequest().getHeader("If-Range"))
    }

    @Test fun `chunked full download succeeds but truncated range never commits`() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(payload), 1024))
        downloadZipTo(url, target, client)
        assertComplete()
        server.takeRequest()

        seed()
        server.enqueue(tail(4096).setChunkedBody("truncated", 4))
        try {
            downloadZipTo(url, target, client)
            fail("incomplete range must not commit")
        } catch (_: IOException) {
            assertFalse(target.exists())
            assertNull(StageStore.readManifest(meta))
        }
    }

    @Test fun `unexpected content encoding preserves partial without committing`() = runBlocking {
        seed()
        server.enqueue(tail(4096).setHeader("Content-Encoding", "gzip"))
        try {
            downloadZipTo(url, target, client)
            fail("encoded bytes must not be mixed with identity bytes")
        } catch (_: IOException) {
            assertFalse(target.exists())
            assertEquals(4096L, part.length())
        }
    }
}
