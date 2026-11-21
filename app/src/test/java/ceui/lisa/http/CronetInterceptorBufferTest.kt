package ceui.lisa.http

import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CronetInterceptorBufferTest {

    @Test
    fun appendCopiesDirectBufferBytesWithoutOverflow() {
        val out = Buffer()
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val src = ByteBuffer.allocateDirect(payload.size)
        src.put(payload)
        assertTrue(CronetInterceptor.appendResponseBytes(out, src, 16))
        assertArrayEquals(payload, out.snapshot().toByteArray())
        assertEquals(0, src.position())
        assertEquals(src.capacity(), src.limit())
    }

    @Test
    fun appendAllowsWritingUpToTheLimit() {
        val out = Buffer()
        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(5, 6, 7, 8)
        assertTrue(appendHeap(out, first, 8))
        assertTrue(appendHeap(out, second, 8))
        assertEquals(8, out.size)
        assertArrayEquals(first + second, out.snapshot().toByteArray())
    }

    @Test
    fun appendRejectsBytesThatWouldExceedTheLimit() {
        val out = Buffer()
        assertTrue(appendHeap(out, ByteArray(10) { 1 }, 16))
        val leftover = ByteBuffer.wrap(ByteArray(7) { 2 })
        leftover.position(7)
        assertFalse(CronetInterceptor.appendResponseBytes(out, leftover, 16))
        assertEquals(10, out.size)
        assertArrayEquals(ByteArray(10) { 1 }, out.snapshot().toByteArray())
        // Failed append must not clear the Cronet buffer (flip only).
        assertEquals(0, leftover.position())
        assertEquals(7, leftover.limit())
    }

    @Test
    fun bodyOfHandsTheBufferToTheResponseSource() {
        val payload = ByteArray(100) { it.toByte() }
        val buffer = Buffer().write(payload)
        val body = CronetInterceptor.bodyOf(buffer, "application/json".toMediaType())
        assertEquals(payload.size.toLong(), body.contentLength())
        assertEquals("application/json", body.contentType().toString())
        assertArrayEquals(payload, body.bytes())
    }

    @Test
    fun publishedLimitMatchesTenMegabytes() {
        assertEquals(10 * 1024 * 1024, CronetInterceptor.maxResponseBytes())
    }

    private fun appendHeap(out: Buffer, payload: ByteArray, maxBytes: Int): Boolean {
        val src = ByteBuffer.wrap(payload)
        src.position(payload.size)
        return CronetInterceptor.appendResponseBytes(out, src, maxBytes)
    }
}
