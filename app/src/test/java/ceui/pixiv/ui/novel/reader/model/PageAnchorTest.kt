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

    @Test fun `zero-width translation page before a boundary does not capture the anchor`() {
        // 第 1 页只有双语译文的尾巴（零宽度，charStart == charEnd == 120），锚点 120 跳过它落到下一段原文。
        val list = pages(0 to 120, 120 to 120, 130 to 260)
        assertEquals(2, list.indexOfPageForAnchor(120))
    }
}
