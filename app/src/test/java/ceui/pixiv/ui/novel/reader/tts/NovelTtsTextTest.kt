package ceui.pixiv.ui.novel.reader.tts

import ceui.pixiv.ui.novel.reader.model.ContentToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class NovelTtsTextTest {

    @Test
    fun mappedSegmentsKeepIndentAndMarkupGapsInReaderCoordinates() {
        val tokens = listOf(
            ContentToken.Paragraph(0, 7, "first", textSourceStart = 2),
            ContentToken.PixivImage(8, 25, 42, 0),
            ContentToken.Chapter(26, 43, "Chapter"),
            ContentToken.Paragraph(44, 55, "second", textSourceStart = 46),
        )
        val parts = NovelTtsText.segmentsFromTokens(tokens)
        assertEquals(listOf("first", "Chapter", "second"), parts.map { it.text })
        assertEquals(2..6, parts[0].sourceRange())
        assertEquals(26..42, parts[1].sourceRange(1, 3))
        assertEquals(47..49, parts[2].sourceRange(1, 4))
        assertEquals(2, NovelTtsText.paragraphStart(tokens, 5))
        assertEquals(46, NovelTtsText.paragraphStart(tokens, 50))
        assertNull(NovelTtsText.paragraphStart(tokens, 10))
    }

    @Test
    fun startingOnLaterPageAndEngineResumeOffsetsMapToSameText() {
        val tokens = listOf(ContentToken.Paragraph(100, 122, "abcdefghijklmnopqrst", 102))
        val part = NovelTtsText.segmentsFromTokens(tokens, startCharIndex = 109).single()
        assertEquals("hijklmnopqrst", part.text)
        assertEquals(109..121, part.sourceRange())
        // Resumed utterance begins at offset 3, then the engine reports [2, 5).
        assertEquals(114..116, part.sourceRange(3 + 2, 3 + 5))
        assertNull(part.sourceRange(-1, 2))
        assertNull(part.sourceRange(0, 99))
        assertNull(part.sourceRange(1, 1))
    }

    @Test
    fun splittingLongParagraphRetainsOffsetsAcrossTrimmedSpacesAndSurrogates() {
        val text = "  abc。  def！  😀xyz。 end  "
        val parts = NovelTtsText.segmentsFromTokens(
            listOf(ContentToken.Paragraph(100, 100 + text.length, text)), maxChars = 6,
        )
        assertTrue(parts.size > 1)
        for (part in parts) {
            assertTrue(part.text.length <= 6)
            assertEquals(part.text, text.substring(part.sourceStart - 100, part.sourceEnd - 100))
            assertTrue(!part.text.first().isLowSurrogate())
            assertTrue(!part.text.last().isHighSurrogate())
        }
        assertEquals(text.filterNot(Char::isWhitespace), parts.joinToString("") { it.text }.filterNot(Char::isWhitespace))
    }

    @Test
    fun fromTokensSkipsNonSpokenMarkupAndHonorsStartOffset() {
        val tokens = listOf(
            ContentToken.Paragraph(0, 3, "one"),
            ContentToken.PixivImage(3, 20, 42, 0),
            ContentToken.Paragraph(20, 26, "two"),
            ContentToken.Jump(26, 35, 2),
        )

        assertEquals("Title\none\ntwo", NovelTtsText.fromTokens(tokens, "Title"))
        assertEquals("two", NovelTtsText.fromTokens(tokens, startCharIndex = 20))
    }

    @Test
    fun fromTokensStartsInsideParagraphWithoutRepeatingPreviousText() {
        val tokens = listOf(ContentToken.Paragraph(10, 20, "abcdefghij"))

        assertEquals("fghij", NovelTtsText.fromTokens(tokens, startCharIndex = 15))
    }

    @Test
    fun splitPrefersSentenceBoundaryAndNeverExceedsLimit() {
        val text = "甲甲甲。乙乙乙！丙丙丙？丁丁丁"
        val parts = NovelTtsText.split(text, maxChars = 7).map { it.text }

        assertEquals(listOf("甲甲甲。", "乙乙乙！", "丙丙丙？丁丁丁"), parts)
        assertTrue(parts.all { it.length <= 7 })
    }

    @Test
    fun japaneseScriptSelectsJapaneseLocaleAndShorterTurns() {
        val locale = NovelTtsText.detectLocale("これは日本語の本文です。")

        assertEquals(Locale.JAPAN, locale)
        assertEquals(NovelTtsText.JAPANESE_MAX_CHARS, NovelTtsText.maxCharsFor(locale))
    }

    @Test
    fun textWithoutJapaneseKanaKeepsFallbackLocale() {
        val fallback = Locale.US

        assertEquals(fallback, NovelTtsText.detectLocale("An English paragraph.", fallback))
        assertEquals(NovelTtsText.DEFAULT_MAX_CHARS, NovelTtsText.maxCharsFor(fallback))
    }
}
