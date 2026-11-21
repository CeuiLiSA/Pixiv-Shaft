package ceui.lisa.utils

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Stream helpers that are safe on this project's [minSdk] 24.
 *
 * Do **not** call [InputStream.readAllBytes], [InputStream.readNBytes], or
 * [InputStream.transferTo] here. Those methods were added to the Android
 * platform in API 33. Language level (Java 17) and `coreLibraryDesugaring`
 * do not automatically make them available: this module uses
 * `com.android.tools:desugar_jdk_libs:2.1.3` (not `desugar_jdk_libs_nio`),
 * and that configuration does not retarget these `InputStream` methods.
 * A `compileSdk 36` build would still throw [NoSuchMethodError] on API
 * 24–32 — the same class of bug documented for lint `NewApi` in
 * `app/build.gradle` (issue #953). [ceui.pixiv.ui.common.ModelArchiveInstaller]
 * already avoided `readNBytes` for this reason.
 *
 * These helpers never consult [InputStream.available] (it is not a length
 * and may be 0 while more data remains). The caller owns closing the stream.
 */
object StreamIo {

    const val DEFAULT_MAX_BYTES: Int = 8 * 1024 * 1024

    private const val BUFFER_SIZE = 8192

    /**
     * Reads the remainder of [input] into a new array.
     *
     * @param maxBytes reject the read if the stream is larger than this; the
     *   default is for small assets / text, not multi-megabyte downloads.
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

    /**
     * Reads up to [count] bytes, returning a shorter array if EOF is reached
     * first. Same contract as the API 33 [InputStream.readNBytes] overload
     * that takes a length, implemented with a short-read-safe loop.
     */
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
        val buf = ByteArray(BUFFER_SIZE)
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
