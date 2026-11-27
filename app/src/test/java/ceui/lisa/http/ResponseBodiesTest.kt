package ceui.lisa.http

import ceui.lisa.fragments.WebNovelParser
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.Buffer
import okio.Timeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.charset.StandardCharsets

class ResponseBodiesTest {

    private val gson = Gson()

    @Test
    fun fromJsonAssemblesShortReads() {
        val json = """{"v":42}"""
        val body = TrackingResponseBody(oneByteBody(json, "application/json"))
        val parsed = body.fromJson(gson, Box::class.java)
        assertEquals(42, parsed.v)
        assertEquals(1, body.closeCount)
    }

    @Test
    fun fromJsonHonorsHttpCharsetAndUtf16() {
        val json = """{"v":7}"""
        val bytes = json.toByteArray(StandardCharsets.UTF_16)
        val body = bytes.toResponseBody("application/json; charset=utf-16".toMediaType())
        assertEquals(7, body.fromJson(gson, Box::class.java).v)
    }

    @Test
    fun fromJsonSkipsUtf8Bom() {
        val json = """{"v":9}"""
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + json.toByteArray()
        val body = bytes.toResponseBody("application/json".toMediaType())
        assertEquals(9, body.fromJson(gson, Box::class.java).v)
    }

    @Test
    fun fromJsonClosesTheBodyWhenParsingFails() {
        val body = TrackingResponseBody("""{"v":"x"}""".toResponseBody("application/json".toMediaType()))
        assertThrows(JsonSyntaxException::class.java) {
            body.fromJson(gson, Box::class.java)
        }
        assertEquals(1, body.closeCount)
    }

    @Test
    fun fromJsonPropagatesReadFailureAndCloses() {
        val body = TrackingResponseBody(failingBody())
        val error = assertThrows(IOException::class.java) {
            body.fromJson(gson, Box::class.java)
        }
        assertEquals("boom", error.message)
        assertEquals(1, body.closeCount)
    }

    @Test
    fun parseHtmlUsesContentTypeCharset() {
        val html = "<html><body>caf\u00e9</body></html>"
        val bytes = html.toByteArray(StandardCharsets.ISO_8859_1)
        val body = bytes.toResponseBody("text/html; charset=ISO-8859-1".toMediaType())
        assertEquals("café", body.parseHtml().body().text())
    }

    @Test
    fun parseHtmlDetectsMetaCharsetWhenHeaderHasNone() {
        val html = (
            "<html><head><meta charset=\"ISO-8859-1\"></head><body>caf".toByteArray(StandardCharsets.ISO_8859_1) +
                byteArrayOf(0xE9.toByte()) +
                "</body></html>".toByteArray(StandardCharsets.ISO_8859_1)
            )
        val body = html.toResponseBody("text/html".toMediaType())
        assertEquals("café", body.parseHtml().body().text())
    }

    @Test
    fun parsePixivObjectFromBodyMatchesStringParser() {
        val html = pixivHtml("hello body")
        val fromString = WebNovelParser.parsePixivObject(html)
        val body = TrackingResponseBody(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
        val fromBody = WebNovelParser.parsePixivObject(body)
        assertEquals("hello body", fromString?.novel?.text)
        assertEquals(fromString?.novel?.text, fromBody?.novel?.text)
        assertEquals(1, body.closeCount)
    }

    @Test
    fun parsePixivObjectReturnsNullWhenScriptIsMissing() {
        val html = "<html><body><script>var x = 1;</script></body></html>"
        assertNull(WebNovelParser.parsePixivObject(html.toResponseBody("text/html".toMediaType())))
    }

    private fun oneByteBody(text: String, mediaType: String): ResponseBody {
        val data = text.toByteArray()
        return OneByteSource(data).buffer().asResponseBody(mediaType.toMediaType(), data.size.toLong())
    }

    private fun failingBody(): ResponseBody {
        return FailingSource().buffer().asResponseBody("application/json".toMediaType(), 8)
    }

    private fun pixivHtml(text: String): String = """
        <html><body>
        <script>
        Object.defineProperty(window, 'pixiv', {
          value: {"novel":{"text":"$text"}}
        });
        </script>
        </body></html>
    """.trimIndent()

    data class Box(val v: Int)
}

private class TrackingResponseBody(
    private val delegate: ResponseBody,
) : ResponseBody() {
    var closeCount = 0
        private set

    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun source() = delegate.source()
    override fun close() {
        closeCount++
        delegate.close()
    }
}

private class OneByteSource(private val data: ByteArray) : okio.Source {
    private var index = 0
    override fun read(sink: Buffer, byteCount: Long): Long {
        if (index >= data.size) return -1
        if (byteCount <= 0) return 0
        sink.writeByte(data[index].toInt())
        index++
        return 1
    }
    override fun timeout() = Timeout.NONE
    override fun close() = Unit
}

private class FailingSource : okio.Source {
    override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("boom")
    override fun timeout() = Timeout.NONE
    override fun close() = Unit
}
