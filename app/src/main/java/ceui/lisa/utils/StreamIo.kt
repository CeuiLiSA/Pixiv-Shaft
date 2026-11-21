package ceui.lisa.utils

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.io.DEFAULT_BUFFER_SIZE

/**
 * Stream helpers for minSdk 24.
 *
 * Do not call [InputStream.readAllBytes], [InputStream.readNBytes], or
 * [InputStream.transferTo]: they are API 33 and are not retargeted by this
 * project's `desugar_jdk_libs` 2.1.3, so they would throw [NoSuchMethodError]
 * on API 24–32.
 *
 * These helpers never use [InputStream.available] as a length. The caller
 * owns closing the stream.
 */
object StreamIo {

    const val DEFAULT_MAX_BYTES: Int = 8 * 1024 * 1024

    /**
     * Reads the remainder of [input] into a new array.
     *
     * @param maxBytes abort if the stream is larger; default is for small
     *   assets, not multi-megabyte downloads.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun readAllBytes(input: InputStream, maxBytes: Int = DEFAULT_MAX_BYTES): ByteArray {
        require(maxBytes >= 0) { "maxBytes must be >= 0" }
        val out = ByteArrayOutputStream()
        copy(input, out, maxBytes.toLong())
        return out.toByteArray()
    }

    /** Reads up to [count] bytes, or fewer if EOF is reached first. */
    @JvmStatic
    @Throws(IOException::class)
    fun readNBytes(input: InputStream, count: Int): ByteArray {
        require(count >= 0) { "count must be >= 0" }
        if (count == 0) return ByteArray(0)
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) return bytes.copyOf(offset)
            offset += read
        }
        return bytes
    }

    /** Copies [input] to [output] until EOF. Returns bytes written. */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long = Long.MAX_VALUE): Long {
        require(maxBytes >= 0) { "maxBytes must be >= 0" }
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (total + n > maxBytes) {
                throw IOException("Stream exceeds $maxBytes bytes")
            }
            output.write(buf, 0, n)
            total += n
        }
        return total
    }
}
