package ceui.pixiv.ui.pinned

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedSearchTermsTest {

    @Test
    fun `split drops blanks and collapses whitespace like the search chip row`() {
        assertEquals(listOf("原神", "胡桃"), splitSearchTerms("  原神   胡桃 "))
        assertEquals(listOf("原神", "胡桃"), splitSearchTerms("原神\t胡桃"))
        assertEquals(emptyList<String>(), splitSearchTerms("   "))
        assertEquals(emptyList<String>(), splitSearchTerms(null))
    }

    @Test
    fun `AND combination ignores order`() {
        assertTrue(sameSearchTerms(listOf("原神", "胡桃"), listOf("胡桃", "原神")))
        assertTrue(sameSearchTerms(listOf("原神"), listOf("原神")))
    }

    @Test
    fun `different or partial combinations are not the same`() {
        assertFalse(sameSearchTerms(listOf("原神", "胡桃"), listOf("原神")))
        assertFalse(sameSearchTerms(listOf("原神", "胡桃"), listOf("原神", "甘雨")))
    }

    @Test
    fun `OR queries only match in the exact order`() {
        val a = listOf("原神", "OR", "胡桃", "水着")
        assertTrue(sameSearchTerms(a, a.toList()))
        assertFalse(sameSearchTerms(a, listOf("原神", "水着", "OR", "胡桃")))
    }

    @Test
    fun `display name joins with plus`() {
        assertEquals("原神 + 胡桃", searchTermsDisplayName(listOf("原神", "胡桃")))
        assertEquals("原神", searchTermsDisplayName(listOf("原神")))
    }
}
