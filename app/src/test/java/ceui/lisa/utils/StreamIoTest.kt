package ceui.lisa.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * [StreamIo] must not treat [InputStream.available] as a length, must tolerate
 * short reads, and must leave closing to the caller (typically try-with-resources
 * / `use`).
 */
class StreamIoTest {

    @Test
    fun `available 0 still yields remaining data`() {
        val payload = "hello-available-zero".toByteArray()
        val stream = ScriptedStream(chunk(payload), availableValue = 0)
        stream.use {
            assertEquals(0, it.available())
            assertArrayEquals(payload, StreamIo.readAllBytes(it))
        }
        assertEquals(1, stream.closeCount)
    }

    @Test
    fun `short reads are assembled into the full payload`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val stream = ScriptedStream(
            listOf(byteArrayOf(1), byteArrayOf(2, 3), byteArrayOf(4, 5, 6, 7)),
        )
        stream.use {
            assertArrayEquals(payload, StreamIo.readAllBytes(it))
        }
    }

    @Test
    fun `readNBytes stops at EOF and returns a short array`() {
        val stream = ScriptedStream(listOf(byteArrayOf(9, 8, 7)))
        stream.use {
            assertArrayEquals(byteArrayOf(9, 8, 7), StreamIo.readNBytes(it, 16))
        }
    }

    @Test
    fun `readNBytes honors short reads when filling an exact count`() {
        val stream = ScriptedStream(listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)))
        stream.use {
            assertArrayEquals(byteArrayOf(1, 2, 3), StreamIo.readNBytes(it, 3))
        }
    }

    @Test
    fun `copy writes every byte without consulting available`() {
        val payload = ByteArray(10_000) { it.toByte() }
        val stream = ScriptedStream(payload.asChunks(3), availableValue = null)
        val out = ByteArrayOutputStream()
        stream.use {
            assertEquals(payload.size.toLong(), StreamIo.copy(it, out))
        }
        assertArrayEquals(payload, out.toByteArray())
        assertFalse("available() must not be used to size the copy", stream.availableCalled)
    }

    @Test
    fun `empty stream yields empty arrays`() {
        ScriptedStream(emptyList()).use {
            assertArrayEquals(ByteArray(0), StreamIo.readAllBytes(it))
        }
        ScriptedStream(emptyList()).use {
            assertArrayEquals(ByteArray(0), StreamIo.readNBytes(it, 4))
        }
    }

    @Test
    fun `exceeding maxBytes fails and caller still closes the stream`() {
        val stream = ScriptedStream(listOf(ByteArray(8) { 1 }))
        stream.use {
            val error = assertThrows(IOException::class.java) {
                StreamIo.readAllBytes(it, maxBytes = 4)
            }
            assertTrue(error.message, error.message!!.contains("exceeds"))
        }
        assertEquals(1, stream.closeCount)
    }

    @Test
    fun `helper does not close the stream and use still closes after a mid-read failure`() {
        val stream = ScriptedStream(listOf(byteArrayOf(1, 2), byteArrayOf(3)), failOnChunk = 1)
        val error = assertThrows(IOException::class.java) {
            stream.use { StreamIo.readAllBytes(it) }
        }
        assertEquals("boom", error.message)
        assertEquals("use must close after the failed read", 1, stream.closeCount)
    }

    private fun chunk(payload: ByteArray) = listOf(payload.copyOf())

    private fun ByteArray.asChunks(size: Int): List<ByteArray> =
        (indices step size).map { start -> copyOfRange(start, minOf(start + size, this.size)) }
}

/**
 * [availableValue] `null` means [available] is unsupported and must not be
 * called; any other value is returned as-is (including 0).
 */
private class ScriptedStream(
    private val chunks: List<ByteArray>,
    private val availableValue: Int? = 0,
    private val failOnChunk: Int = -1,
) : InputStream() {
    private var index = 0
    var closeCount = 0
        private set
    var availableCalled = false
        private set

    override fun available(): Int {
        availableCalled = true
        return availableValue ?: throw AssertionError("available() should not be used as a length")
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (index == failOnChunk) throw IOException("boom")
        if (index >= chunks.size) return -1
        val chunk = chunks[index++]
        val n = minOf(len, chunk.size)
        require(n == chunk.size) { "test chunk ${chunk.size} did not fit in dest $len" }
        System.arraycopy(chunk, 0, b, off, n)
        return n
    }

    override fun close() {
        closeCount++
    }
}
