package ceui.pixiv.ui.novel.reader.feature

import ceui.pixiv.ui.novel.reader.model.Page
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchEngineTest {
    @Test
    fun `hit at a page boundary belongs to the next page`() {
        val source = "a".repeat(120) + "needle" + "b".repeat(114)
        val pages = listOf(page(0, 0, 120), page(1, 120, 240))
        val hits = SearchEngine.search(source, "needle", regex = false, caseSensitive = true)
        assertEquals(1, SearchEngine.annotatePageIndices(hits, pages).single().pageIndex)
    }

    @Test
    fun `search skips zero width illustration pages before the matching text`() {
        val source = "a".repeat(120) + "needle"
        val pages = listOf(page(0, 0, 120), page(1, 120, 120), page(2, 120, 126))
        val hits = SearchEngine.search(source, "needle", regex = false, caseSensitive = true)
        assertEquals(2, SearchEngine.annotatePageIndices(hits, pages).single().pageIndex)
    }

    @Test
    fun `hits within pages retain their page indices`() {
        val source = "needle" + "a".repeat(114) + "needle"
        val pages = listOf(page(0, 0, 120), page(1, 120, 126))
        val hits = SearchEngine.search(source, "needle", regex = false, caseSensitive = true)
        assertEquals(listOf(0, 1), SearchEngine.annotatePageIndices(hits, pages).map { it.pageIndex })
    }

    private fun page(index: Int, start: Int, end: Int) =
        Page(index, emptyList(), start, end)
}
