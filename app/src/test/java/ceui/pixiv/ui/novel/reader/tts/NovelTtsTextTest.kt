package ceui.pixiv.ui.novel.reader.tts

import ceui.pixiv.ui.novel.reader.model.ContentToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NovelTtsTextTest {

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
