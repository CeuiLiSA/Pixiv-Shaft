package ceui.pixiv.download

import okhttp3.OkHttpClient
import okhttp3.Request
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

/**
 * 端到端断点续传 —— 用 [MockWebServer] 起一个**真实的** Range-aware HTTP 服务，跑
 * [StageStore] 判定 + 实际 OkHttp `Range` / `If-Range` 往返 + 真实文件写入，验证：
 *
 *   1. 传输中途断链 → `.part` 留下部分字节 → 再来一次带 `Range` → `206` → 追加 → 字节完整；
 *   2. `If-Range` validator 不匹配（资源变了）→ 服务器回 `200` 全量 → 截断重写，不损坏；
 *   3. `.part` 已持有全部字节 → `Range` 得 `416` → 判为已完成，不重下；
 *   4. `206` 起点对不上 → 判 ABORT → 弃 `.part`，交给上层整段重下。
 *
 * 这套逻辑镜像 `Manager.runStagedTransfer` 的网络分支（Manager 本体耦合 Android，无法纯
 * JVM 跑）；[resumeStep] 与之同构，任何一处判定回归都会在这里翻车。
 */
class StageResumeHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val client = OkHttpClient()

    /** 1000 字节确定性 payload。 */
    private val payload = ByteArray(1000) { (it % 251).toByte() }
    private val etag = "\"v1-abc\""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = File.createTempFile("resume_http_", "_d").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    private fun url() = server.url("/img/12345_p0.jpg").toString()

    // ---------- 与 Manager.runStagedTransfer 网络分支同构的续传单步 ----------

    private fun resumeStep(url: String): StageStore.WriteMode {
        val key = StageStore.keyForUrl(url)
        val stageFile = StageStore.partFile(dir, key)
        val metaFile = StageStore.metaFile(dir, key)

        var existing = stageFile.length()
        var mf = if (existing > 0) StageStore.readManifest(metaFile) else null

        // 预检：manifest 已知总长时无需发请求（镜像 Manager）
        if (mf != null && mf.total >= 0) {
            if (existing == mf.total) return StageStore.WriteMode.ALREADY_COMPLETE
            if (existing > mf.total) {
                StageStore.clear(dir, key); existing = 0; mf = null
            }
        }

        val rb = Request.Builder().url(url)
        if (existing > 0) {
            rb.addHeader("Range", "bytes=$existing-")
            val v = mf?.validator
            if (v != null && (mf.validatorType == StageStore.VALIDATOR_ETAG
                        || mf.validatorType == StageStore.VALIDATOR_LASTMOD)) {
                rb.addHeader("If-Range", v)
            }
        }
        client.newCall(rb.build()).execute().use { resp ->
            val code = resp.code
            if (!resp.isSuccessful && code != 416) throw IOException("HTTP $code")
            val body = resp.body
            val cl = body?.contentLength() ?: -1L
            val dec = StageStore.decideWrite(code, resp.header("Content-Range"), cl, existing)
            when (dec.mode) {
                StageStore.WriteMode.ABORT -> StageStore.clear(dir, key)
                StageStore.WriteMode.ALREADY_COMPLETE -> { /* 字节已在 .part */ }
                StageStore.WriteMode.FRESH, StageStore.WriteMode.APPEND -> {
                    StageStore.writeManifest(metaFile, StageStore.buildManifest(
                        url, resp.header("ETag"), resp.header("Last-Modified"), dec.total))
                    val append = dec.mode == StageStore.WriteMode.APPEND
                    FileOutputStream(stageFile, append).use { out ->
                        body!!.byteStream().copyTo(out)   // 断链时 copyTo 抛 IOException，已写字节保留
                    }
                }
            }
            return dec.mode
        }
    }

    private fun stageBytes(url: String): ByteArray =
        StageStore.partFile(dir, StageStore.keyForUrl(url)).readBytes()

    // ---------- Dispatcher ----------

    /** 完整的 Range-aware 服务：无 Range → 200 全量；有 Range → 206 从 start 起。 */
    private fun rangeDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")
            if (range == null) {
                return MockResponse().setResponseCode(200)
                    .setHeader("ETag", etag)
                    .setBody(Buffer().write(payload))
            }
            val start = range.removePrefix("bytes=").substringBefore("-").toInt()
            val slice = payload.copyOfRange(start, payload.size)
            return MockResponse().setResponseCode(206)
                .setHeader("ETag", etag)
                .setHeader("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                .setBody(Buffer().write(slice))
        }
    }

    // ---------- 测试 ----------

    @Test
    fun `interrupted transfer leaves a partial that a Range resume completes`() {
        // 第一次：中途断链（DISCONNECT_DURING_RESPONSE_BODY）→ .part 落下部分字节
        server.dispatcher = object : Dispatcher() {
            @Volatile var served = false
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (!served) {
                    served = true
                    // 声明 1000 字节却中途断开，OkHttp 读到一半抛 IOException
                    MockResponse()
                        .setHeader("ETag", etag)
                        .setBody(Buffer().write(payload))
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                } else {
                    // 续传请求：正常 Range 应答
                    val range = request.getHeader("Range")!!
                    val start = range.removePrefix("bytes=").substringBefore("-").toInt()
                    MockResponse().setResponseCode(206)
                        .setHeader("ETag", etag)
                        .setHeader("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                        .setBody(Buffer().write(payload.copyOfRange(start, payload.size)))
                }
            }
        }

        val u = url()
        // attempt 1：断链 → 抛异常，但 .part 已有部分字节
        var threw = false
        try {
            resumeStep(u)
        } catch (_: IOException) {
            threw = true
        }
        assertTrue("first attempt should be interrupted", threw)
        val partial = stageBytes(u).size
        assertTrue("partial bytes must persist for resume (got $partial)", partial in 1 until payload.size)

        // attempt 2：续传（发 Range）→ 206 → 追加 → 完整
        val mode = resumeStep(u)
        assertEquals(StageStore.WriteMode.APPEND, mode)
        assertArrayEquals("resumed file must equal full payload", payload, stageBytes(u))
    }

    @Test
    fun `pre-seeded partial resumes via 206 append`() {
        val u = url()
        val key = StageStore.keyForUrl(u)
        // 模拟上次留下的 400 字节 partial + manifest
        StageStore.partFile(dir, key).writeBytes(payload.copyOfRange(0, 400))
        StageStore.writeManifest(StageStore.metaFile(dir, key),
            StageStore.buildManifest(u, etag, null, payload.size.toLong()))
        // 注意：manifest.total==1000 != existing 400 → 会走网络续传（不是 ALREADY_COMPLETE）

        server.dispatcher = rangeDispatcher()
        val mode = resumeStep(u)

        assertEquals(StageStore.WriteMode.APPEND, mode)
        assertArrayEquals(payload, stageBytes(u))
        // 校验确实发了 Range: bytes=400-
        val recorded = server.takeRequest()
        assertEquals("bytes=400-", recorded.getHeader("Range"))
        assertEquals(etag, recorded.getHeader("If-Range"))
    }

    @Test
    fun `If-Range mismatch falls back to 200 fresh without corruption`() {
        val u = url()
        val key = StageStore.keyForUrl(u)
        // partial + 一个**过期** validator
        StageStore.partFile(dir, key).writeBytes(ByteArray(400) { 0xFF.toByte() })
        StageStore.writeManifest(StageStore.metaFile(dir, key),
            StageStore.buildManifest(u, "\"STALE\"", null, payload.size.toLong()))

        // 服务器：If-Range 不等于当前 etag → 回 200 全量（忽略 Range）
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val ifRange = request.getHeader("If-Range")
                return if (ifRange != null && ifRange != etag) {
                    MockResponse().setResponseCode(200)
                        .setHeader("ETag", etag)
                        .setBody(Buffer().write(payload))
                } else {
                    val start = request.getHeader("Range")!!
                        .removePrefix("bytes=").substringBefore("-").toInt()
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                        .setBody(Buffer().write(payload.copyOfRange(start, payload.size)))
                }
            }
        }

        val mode = resumeStep(u)
        assertEquals("stale validator → server 200 → FRESH overwrite", StageStore.WriteMode.FRESH, mode)
        assertArrayEquals("must be full payload, not 0xFF-polluted", payload, stageBytes(u))
    }

    @Test
    fun `already-complete partial gets 416 and is not re-downloaded`() {
        val u = url()
        val key = StageStore.keyForUrl(u)
        // 完整字节但**没有 manifest**（跳过预检，逼出真实 416 往返）
        StageStore.partFile(dir, key).writeBytes(payload)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                val start = range?.removePrefix("bytes=")?.substringBefore("-")?.toInt() ?: 0
                return if (start >= payload.size) {
                    MockResponse().setResponseCode(416)
                        .setHeader("Content-Range", "bytes */${payload.size}")
                } else {
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                        .setBody(Buffer().write(payload.copyOfRange(start, payload.size)))
                }
            }
        }

        val mode = resumeStep(u)
        assertEquals(StageStore.WriteMode.ALREADY_COMPLETE, mode)
        assertArrayEquals("bytes untouched", payload, stageBytes(u))
    }

    @Test
    fun `mismatched 206 offset aborts and clears the stage`() {
        val u = url()
        val key = StageStore.keyForUrl(u)
        StageStore.partFile(dir, key).writeBytes(payload.copyOfRange(0, 400)) // 无 manifest

        // 恶意 / 坏代理：不管请求什么 Range，都从 0 发（起点对不上）
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-${payload.size - 1}/${payload.size}")
                    .setBody(Buffer().write(payload))
        }

        val mode = resumeStep(u)
        assertEquals(StageStore.WriteMode.ABORT, mode)
        assertFalse("aborted stage must be cleared for a clean restart",
            StageStore.partFile(dir, key).exists())
    }

    @Test
    fun `fresh download from empty stage writes full payload`() {
        val u = url()
        server.dispatcher = rangeDispatcher()
        val mode = resumeStep(u)
        assertEquals(StageStore.WriteMode.FRESH, mode)
        assertArrayEquals(payload, stageBytes(u))
    }
}
