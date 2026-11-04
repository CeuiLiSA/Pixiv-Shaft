package ceui.pixiv.imageloader

import ceui.pixiv.download.StageStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ResumableOkHttpFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var stagingDir: File
    private val client = OkHttpClient.Builder()
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val payload = ByteArray(1000) { (it % 251).toByte() }
    private val etag = "\"v1-orig\""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDir = File.createTempFile("cache_test_", "_d").apply { delete(); mkdirs() }
        stagingDir = File.createTempFile("staging_test_", "_d").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        cacheDir.listFiles()?.forEach { it.delete() }
        cacheDir.delete()
        stagingDir.listFiles()?.forEach { it.delete() }
        stagingDir.delete()
    }

    private fun testFetcher(): ResumableOkHttpFetcher = ResumableOkHttpFetcher(
        clientProvider = { client },
        contextProvider = { error("Context not expected in unit test") },
        cacheDirProvider = { cacheDir },
        stagingDirProvider = { stagingDir },
    )

    private fun testUrl(): String = server.url("/img-original/img/2026/01/01/00/00/00/12345_p0.jpg").toString()

    @Test
    fun `fresh download from scratch commits completed file`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", etag)
                    .setHeader("Content-Length", payload.size)
                    .setBody(Buffer().write(payload))
            }
        }

        val progressList = mutableListOf<Int>()
        val fetcher = testFetcher()
        val file = fetcher.fetch(testUrl()) { progressList.add(it) }

        assertTrue(file.exists())
        assertEquals(1000L, file.length())
        assertArrayEquals(payload, file.readBytes())
        assertTrue(progressList.isNotEmpty())
        assertEquals(emptyList<File>(), stagingDir.listFiles()?.toList() ?: emptyList<File>())
    }

    @Test
    fun `resumes from partial file when server responds 206`() = runTest {
        val url = testUrl()
        val key = StageStore.keyForUrl(url)
        val partFile = StageStore.partFile(stagingDir, key)
        val metaFile = StageStore.metaFile(stagingDir, key)

        val half = 500
        FileOutputStream(partFile).use { it.write(payload, 0, half) }
        StageStore.writeManifest(metaFile, StageStore.buildManifest(url, etag, null, 1000L))

        var requestedRange: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestedRange = request.getHeader("Range")
                val tail = payload.copyOfRange(half, payload.size)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("ETag", etag)
                    .setHeader("Content-Range", "bytes 500-999/1000")
                    .setHeader("Content-Length", tail.size)
                    .setBody(Buffer().write(tail))
            }
        }

        val progressList = mutableListOf<Int>()
        val fetcher = testFetcher()
        val file = fetcher.fetch(url) { progressList.add(it) }

        assertEquals("bytes=500-", requestedRange)
        assertTrue(file.exists())
        assertEquals(1000L, file.length())
        assertArrayEquals(payload, file.readBytes())
        assertTrue(progressList.any { it >= 50 })
    }

    @Test
    fun `completes directly when server returns 416 with matching total`() = runTest {
        val url = testUrl()
        val key = StageStore.keyForUrl(url)
        val partFile = StageStore.partFile(stagingDir, key)
        val metaFile = StageStore.metaFile(stagingDir, key)

        FileOutputStream(partFile).use { it.write(payload) }
        StageStore.writeManifest(metaFile, StageStore.buildManifest(url, etag, null, 1000L))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse()
                    .setResponseCode(416)
                    .setHeader("Content-Range", "bytes */1000")
            }
        }

        val fetcher = testFetcher()
        val file = fetcher.fetch(url) {}

        assertTrue(file.exists())
        assertEquals(1000L, file.length())
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun `restarts fresh when server ignores range and returns 200`() = runTest {
        val url = testUrl()
        val key = StageStore.keyForUrl(url)
        val partFile = StageStore.partFile(stagingDir, key)
        val metaFile = StageStore.metaFile(stagingDir, key)

        FileOutputStream(partFile).use { it.write(ByteArray(300) { 0xFF.toByte() }) }
        StageStore.writeManifest(metaFile, StageStore.buildManifest(url, etag, null, 1000L))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", "\"v2-new\"")
                    .setHeader("Content-Length", payload.size)
                    .setBody(Buffer().write(payload))
            }
        }

        val fetcher = testFetcher()
        val file = fetcher.fetch(url) {}

        assertTrue(file.exists())
        assertEquals(1000L, file.length())
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun `read timeout retains partial bytes for subsequent resume`() = runTest {
        val url = testUrl()
        val key = StageStore.keyForUrl(url)
        val partFile = StageStore.partFile(stagingDir, key)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val partialBuffer = Buffer().write(payload, 0, 400)
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", payload.size)
                    .setBody(partialBuffer)
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            }
        }

        val fetcher = testFetcher()
        var failed = false
        try {
            fetcher.fetch(url) {}
        } catch (e: IOException) {
            failed = true
        }

        assertTrue(failed)
        assertTrue(partFile.exists())
        assertTrue(partFile.length() in 1..400)
    }
}
