package ceui.pixiv.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRiskPolicyTest {

    private val coreName = text(0x4E60, 0x8FD1, 0x5E73)
    private val movement = text(0x767D, 0x7EB8, 0x8FD0, 0x52A8)

    @Test
    fun `blocks explicit political keyword`() {
        assertTrue(SearchRiskPolicy.shouldWithhold(coreName))
    }

    @Test
    fun `blocks risky term inside multi tag query`() {
        val query = "landscape $movement original"
        assertTrue(SearchRiskPolicy.shouldWithhold(query))
        assertEquals(query, SearchRiskPolicy.withheldQuery("  $query  "))
    }

    @Test
    fun `normalization prevents whitespace punctuation and zero width bypass`() {
        assertTrue(SearchRiskPolicy.shouldWithhold(coreName.toList().joinToString(" ")))
        assertTrue(SearchRiskPolicy.shouldWithhold(coreName.toList().joinToString("\u00B7")))
        assertTrue(SearchRiskPolicy.shouldWithhold(coreName.toList().joinToString("\u200B")))
        assertTrue(
            SearchRiskPolicy.shouldWithhold(
                text(0xFF38, 0xFF49, 0x3000, 0xFF2A, 0xFF49, 0xFF4E, 0xFF50, 0xFF49, 0xFF4E, 0xFF47),
            ),
        )
    }

    @Test
    fun `supports traditional Chinese aliases`() {
        assertTrue(SearchRiskPolicy.shouldWithhold(text(0x7FD2, 0x8FD1, 0x5E73)))
        assertTrue(SearchRiskPolicy.shouldWithhold(text(0x81FA, 0x7063, 0x7368, 0x7ACB)))
        assertTrue(SearchRiskPolicy.shouldWithhold(text(0x96E8, 0x5098, 0x904B, 0x52D5)))
    }

    @Test
    fun `does not block broad or partially similar safe terms`() {
        assertFalse(SearchRiskPolicy.shouldWithhold("chairman pixel art"))
        assertFalse(SearchRiskPolicy.shouldWithhold("democracy and human rights illustration"))
        assertFalse(SearchRiskPolicy.shouldWithhold("12345"))
        assertFalse(SearchRiskPolicy.shouldWithhold(coreName.take(2)))
        assertNull(SearchRiskPolicy.withheldQuery("ordinary landscape"))
    }

    private fun text(vararg codePoints: Int): String = buildString {
        codePoints.forEach { appendCodePoint(it) }
    }
}
