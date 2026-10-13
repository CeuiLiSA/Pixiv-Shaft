package ceui.pixiv.ui.novel.reader.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * issue #864：重排版后按锚点找回所在页。跨页段落的续页页首 == 前一页的 charEnd，
 * 锚点落在交界上必须归后一页，否则全文翻译每批上屏都把读者拽回前一页。
 */
class PageAnchorTest {

    private fun pages(vararg ranges: Pair<Int, Int>): List<Page> =
        ranges.mapIndexed { i, (start, end) -> Page(index = i, elements = emptyList(), charStart = start, charEnd = end) }

    @Test fun `anchor on a page boundary belongs to the page that starts there`() {
        // 第 0 页以一个跨页段落的前半截收尾（charEnd = 120），第 1 页从它的后半截开始。
        val list = pages(0 to 120, 120 to 260, 261 to 400)
        assertEquals(1, list.indexOfPageForAnchor(120))
    }

    @Test fun `anchor inside a page resolves to that page`() {
        val list = pages(0 to 120, 120 to 260, 261 to 400)
        assertEquals(0, list.indexOfPageForAnchor(0))
        assertEquals(1, list.indexOfPageForAnchor(200))
        assertEquals(2, list.indexOfPageForAnchor(261))
    }

    @Test fun `anchor at the end of the book falls back to the last page`() {
        val list = pages(0 to 120, 120 to 260)
        assertEquals(1, list.indexOfPageForAnchor(260))
        assertEquals(1, list.indexOfPageForAnchor(999))
    }

    @Test fun `empty pages resolve to zero`() {
        assertEquals(0, emptyList<Page>().indexOfPageForAnchor(42))
    }

    @Test fun `zero-width page is found again by its start`() {
        // 第 1 页是混排插画（零宽度，charStart == charEnd == 120）。停在插画页上时锚点 = 120，
        // 重排版后必须回到插画页，而不是被推到下一页。
        val list = pages(0 to 120, 120 to 120, 130 to 260)
        assertEquals(1, list.indexOfPageForAnchor(120))
    }

    @Test fun `anchor between pages that matches no page start uses the strict end rule`() {
        // 125 落在第 1 页（零宽度）与第 2 页（130 起）之间的换行符上：归下一页。
        val list = pages(0 to 120, 120 to 120, 130 to 260)
        assertEquals(2, list.indexOfPageForAnchor(125))
    }
}
