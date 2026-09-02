package ceui.pixiv.progress

import ceui.pixiv.progress.internal.ProgressResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 计数流的逐次精确断言：每一次 read 读多少字节、时钟在哪里，都由测试控制，
 * 所以这里比对的是**完整事件序列**，不是「大概发了几次」。
 */
class ProgressResponseBodyTest {

    private val clock = FakeClock()
    private val events = ArrayList<DownloadProgress>()

    private fun wrap(
        body: okhttp3.ResponseBody,
        refreshIntervalMs: Long = 150L,
    ): ProgressResponseBody = ProgressResponseBody(
        body = body,
        refreshIntervalMs = refreshIntervalMs,
        clock = clock,
        sink = { events += it },
    )

    private fun p(bytesRead: Long, contentLength: Long?, isDone: Boolean) =
        DownloadProgress(bytesRead, contentLength, isDone)

    @Test
    fun `first read emits immediately regardless of clock`() {
        val body = wrap(chunkedBody(bytes(100), chunkSize = 10))
        clock.nowMs = 0L // 距「上次」(不存在) 的间隔无从谈起，首次必须报

        body.source().read(Buffer(), 8192)

        assertEquals(listOf(p(10, 100, false)), events)
    }

    @Test
    fun `reads inside the refresh interval are silent`() {
        val body = wrap(chunkedBody(bytes(100), chunkSize = 10))
        val source = body.source()

        source.read(Buffer(), 8192) // t=0 → 报
        clock.advance(149)
        source.read(Buffer(), 8192) // t=149 < 150 → 静默
        source.read(Buffer(), 8192) // t=149 → 静默

        assertEquals(listOf(p(10, 100, false)), events)
    }

    @Test
    fun `a read exactly at the interval boundary emits`() {
        val body = wrap(chunkedBody(bytes(100), chunkSize = 10))
        val source = body.source()

        source.read(Buffer(), 8192) // t=0 → 报 10
        clock.advance(150)
        source.read(Buffer(), 8192) // t=150, 150-0 >= 150 → 报 20

        assertEquals(listOf(p(10, 100, false), p(20, 100, false)), events)
    }

    @Test
    fun `interval is measured from the last emission not from the last read`() {
        val body = wrap(chunkedBody(bytes(100), chunkSize = 10))
        val source = body.source()

        source.read(Buffer(), 8192) // t=0 → 报 10
        clock.advance(100)
        source.read(Buffer(), 8192) // t=100 → 静默 (20)
        clock.advance(100)
        source.read(Buffer(), 8192) // t=200，距上次报 200 >= 150 → 报 30
        clock.advance(100)
        source.read(Buffer(), 8192) // t=300，距上次报 100 → 静默 (40)

        assertEquals(listOf(p(10, 100, false), p(30, 100, false)), events)
    }

    @Test
    fun `zero interval emits on every read`() {
        val body = wrap(chunkedBody(bytes(30), chunkSize = 10), refreshIntervalMs = 0L)

        body.source().drainByReads()

        assertEquals(
            listOf(p(10, 30, false), p(20, 30, false), p(30, 30, true)),
            events,
        )
    }

    @Test
    fun `done fires when bytes read reaches content length even without an EOF read`() {
        val body = wrap(chunkedBody(bytes(30), chunkSize = 10))
        val source = body.source()

        source.read(Buffer(), 8192) // 10 → 首次报
        source.read(Buffer(), 8192) // 20 → 节流静默
        source.read(Buffer(), 8192) // 30 == total → 完成，无视节流

        assertEquals(listOf(p(10, 30, false), p(30, 30, true)), events)
    }

    @Test
    fun `done bypasses the refresh interval`() {
        val body = wrap(chunkedBody(bytes(20), chunkSize = 10), refreshIntervalMs = 10_000L)
        val source = body.source()

        source.read(Buffer(), 8192) // t=0 → 报 10
        source.read(Buffer(), 8192) // t=0 但读满 → 完成

        assertEquals(listOf(p(10, 20, false), p(20, 20, true)), events)
    }

    @Test
    fun `done is reported exactly once even if the consumer keeps reading`() {
        val body = wrap(chunkedBody(bytes(30), chunkSize = 10), refreshIntervalMs = 0L)
        val source = body.source()

        val reads = source.drainByReads()
        // 消费者读到 -1 之后再读几次 -1
        assertEquals(-1L, source.read(Buffer(), 8192))
        assertEquals(-1L, source.read(Buffer(), 8192))

        assertEquals(listOf(10L, 10L, 10L, -1L), reads)
        assertEquals(1, events.count { it.isDone })
        assertEquals(p(30, 30, true), events.last())
        assertEquals(3, events.size)
    }

    @Test
    fun `unknown content length reports null length and completes only on EOF`() {
        val body = wrap(chunkedBody(bytes(25), chunkSize = 10, declaredLength = -1L), refreshIntervalMs = 0L)

        body.source().drainByReads()

        assertEquals(
            listOf(p(10, null, false), p(20, null, false), p(25, null, false), p(25, null, true)),
            events,
        )
    }

    @Test
    fun `server under-reports the length - done fires on the read that crosses it`() {
        // 声明 15 字节，实际 30。读到 20 时已 >= 15 → 完成；之后的字节不再报。
        val body = wrap(chunkedBody(bytes(30), chunkSize = 10, declaredLength = 15L), refreshIntervalMs = 0L)

        val reads = body.source().drainByReads()

        assertEquals(listOf(10L, 10L, 10L, -1L), reads) // 数据一个字节都没少给消费者
        assertEquals(listOf(p(10, 15, false), p(20, 15, true)), events)
    }

    @Test
    fun `server over-reports the length - done fires on EOF with the real byte count`() {
        // 声明 100 字节，实际只有 30：EOF 是唯一的完成信号，且 bytesRead 报真实值。
        val body = wrap(chunkedBody(bytes(30), chunkSize = 10, declaredLength = 100L), refreshIntervalMs = 0L)

        body.source().drainByReads()

        assertEquals(
            listOf(p(10, 100, false), p(20, 100, false), p(30, 100, false), p(30, 100, true)),
            events,
        )
    }

    @Test
    fun `empty body reports a single done event with zero bytes`() {
        val body = wrap(chunkedBody(ByteArray(0), chunkSize = 10))

        val reads = body.source().drainByReads()

        assertEquals(listOf(-1L), reads)
        assertEquals(listOf(p(0, 0, true)), events)
    }

    @Test
    fun `bytes are passed through unmodified`() {
        val data = bytes(1_000)
        val body = wrap(chunkedBody(data, chunkSize = 7))

        val out = body.source().readByteArray()

        assertTrue(data.contentEquals(out))
        assertEquals(p(1_000, 1_000, true), events.last())
        assertEquals(1, events.count { it.isDone })
    }

    @Test
    fun `read failure propagates and emits nothing for the failed read`() {
        val body = wrap(
            chunkedBody(
                bytes(30),
                chunkSize = 10,
                source = ChunkedSource(bytes(30), chunkSize = 10, failAfterBytes = 20),
            ),
            refreshIntervalMs = 0L,
        )
        val source = body.source()

        source.read(Buffer(), 8192)
        source.read(Buffer(), 8192)
        val thrown = assertThrows(IOException::class.java) { source.read(Buffer(), 8192) }

        assertEquals("simulated read failure", thrown.message)
        assertEquals(listOf(p(10, 30, false), p(20, 30, false)), events)
    }

    @Test
    fun `listener failure surfaces from read and no byte is lost or double counted`() {
        val body = ProgressResponseBody(
            body = chunkedBody(bytes(30), chunkSize = 10),
            refreshIntervalMs = 0L,
            clock = clock,
            sink = { events += it; if (it.bytesRead == 10L) throw IllegalStateException("boom") },
        )
        val source = body.source()
        val consumed = Buffer()

        assertThrows(IllegalStateException::class.java) { source.read(consumed, 8192) }
        // 抛异常那一刻，那 10 字节已经进了外层 BufferedSource 的缓冲：下一次 read 直接从缓冲吐，
        // 不会再经过计数流，所以没有新事件；再下一次才真正读底层，报 20。
        assertEquals(10L, source.read(consumed, 8192))
        assertEquals(10L, source.read(consumed, 8192))
        assertEquals(10L, source.read(consumed, 8192))
        assertEquals(-1L, source.read(consumed, 8192))

        assertTrue(bytes(30).contentEquals(consumed.readByteArray()))
        assertEquals(listOf(p(10, 30, false), p(20, 30, false), p(30, 30, true)), events)
    }

    @Test
    fun `close does not emit and closes the delegate`() {
        val inner = ChunkedSource(bytes(30), chunkSize = 10)
        val body = wrap(chunkedBody(bytes(30), chunkSize = 10, source = inner))
        val source = body.source()

        source.read(Buffer(), 8192)
        source.close()

        assertEquals(listOf(p(10, 30, false)), events)
        assertTrue(inner.closed)
    }

    @Test
    fun `content type and length delegate and source is created once`() {
        val mediaType = "image/png".toMediaType()
        val body = wrap(chunkedBody(bytes(30), chunkSize = 10, mediaType = mediaType))

        assertSame(mediaType, body.contentType())
        assertEquals(30L, body.contentLength())
        assertSame(body.source(), body.source())
    }

    @Test
    fun `content length is sampled once and not re-queried per read`() {
        var queries = 0
        val delegate = object : okhttp3.ResponseBody() {
            private val buffered = ChunkedSource(bytes(30), chunkSize = 10).buffer()
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength(): Long { queries++; return 30L }
            override fun source(): okio.BufferedSource = buffered
        }
        val body = wrap(delegate, refreshIntervalMs = 0L)

        body.source().drainByReads()

        assertEquals(1, queries)
        assertEquals(p(30, 30, true), events.last())
    }
}
