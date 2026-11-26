package ceui.pixiv.ui.pinned

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedSearchMatcherTest {

    @Test
    fun `empty or blank query matches everything`() {
        assertEquals(emptyList<String>(), PinnedSearchMatcher.tokenize(""))
        assertEquals(emptyList<String>(), PinnedSearchMatcher.tokenize("   \u3000\t "))
        assertTrue(PinnedSearchMatcher.matches(emptyList(), "原神"))
    }

    @Test
    fun `matches english case-insensitively and full-width ASCII`() {
        val tokens = PinnedSearchMatcher.tokenize("genshin r-18")
        assertTrue(PinnedSearchMatcher.matches(tokens, "GenshinImpact", "Ｒ－１８"))
        assertFalse(PinnedSearchMatcher.matches(tokens, "GenshinImpact", "all-ages"))
    }

    @Test
    fun `matches japanese across hiragana, katakana, and half-width katakana`() {
        val hiraganaQuery = PinnedSearchMatcher.tokenize("みく")
        assertTrue(PinnedSearchMatcher.matches(hiraganaQuery, "初音ミク"))
        assertTrue(PinnedSearchMatcher.matches(hiraganaQuery, "初音ﾐｸ"))

        val katakanaQuery = PinnedSearchMatcher.tokenize("フータオ")
        assertTrue(PinnedSearchMatcher.matches(katakanaQuery, "ふーたお"))
    }

    @Test
    fun `matches chinese translations and Japanese originals on the same tag`() {
        val zhTokens = PinnedSearchMatcher.tokenize("风景")
        assertTrue(PinnedSearchMatcher.matches(zhTokens, "風景", "风景"))

        val jaTokens = PinnedSearchMatcher.tokenize("風景")
        assertTrue(PinnedSearchMatcher.matches(jaTokens, "風景", "风景"))
    }

    @Test
    fun `matches multi-word query in any order across author name, account, and uid`() {
        val tokens = PinnedSearchMatcher.tokenize("fuzi 藤ちょこ")
        assertTrue(PinnedSearchMatcher.matches(tokens, "藤ちょこ（藤原）", "fuzichoco", "27517"))

        val uidTokens = PinnedSearchMatcher.tokenize("27517")
        assertTrue(PinnedSearchMatcher.matches(uidTokens, "藤ちょこ（藤原）", "fuzichoco", "27517"))
        assertFalse(PinnedSearchMatcher.matches(PinnedSearchMatcher.tokenize("99999"), "藤ちょこ", "fuzichoco", "27517"))
    }

    @Test
    fun `precomputed index matches identically across multilingual fields and handles empty index`() {
        val index = PinnedSearchMatcher.buildIndex("初音ミク", "Miku", "  ")
        assertTrue(PinnedSearchMatcher.matchesIndex(PinnedSearchMatcher.tokenize("みく miku"), index))
        assertFalse(PinnedSearchMatcher.matchesIndex(PinnedSearchMatcher.tokenize("リン"), index))
        assertFalse(PinnedSearchMatcher.matchesIndex(PinnedSearchMatcher.tokenize("miku"), emptyList()))
        assertTrue(PinnedSearchMatcher.matchesIndex(emptyList(), emptyList()))
    }
}
