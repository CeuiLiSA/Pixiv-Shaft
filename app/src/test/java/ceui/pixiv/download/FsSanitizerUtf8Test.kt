package ceui.pixiv.download

import ceui.pixiv.download.sanitize.FsSanitizer
import org.junit.Assert.assertEquals
import org.junit.Test

class FsSanitizerUtf8Test {

    @Test fun `caps supplementary characters by their UTF-8 byte length`() {
        val cleaned = FsSanitizer.cleanSegment("😀".repeat(100))
        assertEquals("😀".repeat(50), cleaned)
        assertEquals(FsSanitizer.MAX_SEGMENT_BYTES, cleaned.toByteArray(Charsets.UTF_8).size)
    }

    @Test fun `does not split a surrogate pair at the filename boundary`() {
        val stem = "a".repeat(195)
        val cleaned = FsSanitizer.cleanSegment(stem + "😀.png", preserveExtension = true)
        assertEquals("$stem.png", cleaned)
    }

    @Test fun `preserves normal extensions on long emoji filenames`() {
        val cleaned = FsSanitizer.cleanSegment("😀".repeat(100) + ".png", preserveExtension = true)
        assertEquals("😀".repeat(49) + ".png", cleaned)
    }

    @Test fun `caps oversized extensions while reserving a nonempty stem`() {
        val cleaned = FsSanitizer.cleanSegment("name." + "x".repeat(300), preserveExtension = true)
        assertEquals("n." + "x".repeat(198), cleaned)
        assertEquals(FsSanitizer.MAX_SEGMENT_BYTES, cleaned.toByteArray(Charsets.UTF_8).size)
    }

    @Test fun `oversized multibyte extension leaves room for fallback stem`() {
        val cleaned = FsSanitizer.cleanSegment("😀." + "夏".repeat(100), preserveExtension = true)
        assertEquals("_." + "夏".repeat(66), cleaned)
        assertEquals(FsSanitizer.MAX_SEGMENT_BYTES, cleaned.toByteArray(Charsets.UTF_8).size)
    }
}
