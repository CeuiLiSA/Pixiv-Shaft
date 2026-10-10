package ceui.pixiv.ui.novel.reader.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWebFontTest {

    @Test
    fun `ids are unique and never collide with built-in fonts`() {
        val ids = ReaderWebFont.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val builtIn = PresetFonts.BUILT_IN.map { it.id }.toSet()
        ids.forEach { assertFalse(it in builtIn) }
        ReaderWebFont.entries.forEach { assertEquals(it, ReaderWebFont.byId(it.id)) }
    }

    @Test
    fun `download urls are pinned to one google fonts commit`() {
        ReaderWebFont.entries.forEach { font ->
            assertTrue(
                font.downloadUrl,
                font.downloadUrl.startsWith(
                    "https://github.com/google/fonts/raw/${ReaderWebFont.GOOGLE_FONTS_COMMIT}/ofl/",
                ),
            )
            assertFalse(font.downloadUrl, font.downloadUrl.contains('[') || font.downloadUrl.contains(']'))
        }
    }

    @Test
    fun `checksum match requires both size and digest`() {
        val font = ReaderWebFont.ZEN_MARU_GOTHIC
        val digest = "a0c0b53543e0993ae2225e629c833f3d51495ad31720694ff112ce4ce11111ef"
        assertTrue(font.matches(font.byteSize, digest))
        assertFalse(font.matches(font.byteSize - 1, digest))
        assertFalse(font.matches(font.byteSize, digest.replaceFirst('a', 'b')))
    }
}
