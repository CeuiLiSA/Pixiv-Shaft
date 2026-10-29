package ceui.pixiv.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FeedUiState.visibleItems] 是「渲染出来的列表」的唯一口径：adapter 位置、空态、全屏错误都按它算，
 * `:app` 里拿下标当 adapter 位置的地方（详情返回跟滚等）也依赖它与 adapter 前缀一一对应。
 */
class FeedUiStateVisibleItemsTest {

    private data class Row(val id: Int, override val hidden: Boolean = false) : FeedItem {
        override val feedKey: Any get() = id
    }

    @Test
    fun `no hidden items returns the same list instance`() {
        // 绝大多数页面每次渲染都走这条：不能因为多了一层派生就额外分配
        val items = listOf(Row(1), Row(2), Row(3))
        assertSame(items, FeedUiState(items = items).visibleItems)
    }

    @Test
    fun `hidden items are skipped and order is kept`() {
        val state = FeedUiState(items = listOf(Row(1), Row(2, hidden = true), Row(3), Row(4, hidden = true)))
        assertEquals(listOf(1, 3), state.visibleItems.map { (it as Row).id })
        // 隐藏项仍然留在数据里，取消隐藏才能原地回来
        assertEquals(4, state.items.size)
    }

    @Test
    fun `all hidden counts as nothing to show`() {
        val items = listOf(Row(1, hidden = true), Row(2, hidden = true))
        val loaded = FeedUiState(items = items, hasLoadedOnce = true)
        assertTrue(loaded.showEmptyState)

        val errored = FeedUiState(items = items, refresh = LoadState.Error(RuntimeException()))
        assertTrue(errored.showFullscreenError)
    }

    @Test
    fun `one visible item is not empty`() {
        val state = FeedUiState(items = listOf(Row(1, hidden = true), Row(2)), hasLoadedOnce = true)
        assertFalse(state.showEmptyState)
    }

    @Test
    fun `hasVisibleItems answers emptiness without building the filtered list`() {
        // 分页链路每翻一页问一次，这里只要是非答案
        assertTrue(FeedUiState(items = listOf(Row(1), Row(2, hidden = true))).hasVisibleItems)
        assertFalse(FeedUiState(items = listOf(Row(1, hidden = true))).hasVisibleItems)
        assertFalse(FeedUiState().hasVisibleItems)
    }
}
