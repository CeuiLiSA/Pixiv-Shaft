package ceui.pixiv.ui.detail

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ViewerPageLink] 的两条契约：翻页广播只投给**当下活着**的收集器（不重放），回传的视口矩形只留最新一份。
 *
 * 「不重放」这条是设计里最容易改坏的地方：详情页视图重建（旋屏 / 回退栈重显）会重新收集，
 * 一旦被换成 StateFlow 那种重放语义，旧值就会把用户自己后来滚出来的位置顶掉 —— 那正是这个类
 * 当初不用 StateFlow 的原因。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerPageLinkTest {

    @Test
    fun `publish reaches an active collector`() = runTest {
        val received = mutableListOf<ViewerPageLink.Ping>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ViewerPageLink.pings.collect { received += it }
        }

        ViewerPageLink.publish(illustId = 101L, entryPage = 0, page = 3)

        assertEquals(listOf(ViewerPageLink.Ping(101L, 0, 3)), received)
    }

    @Test
    fun `publish before anyone collects is dropped, not replayed`() = runTest {
        ViewerPageLink.publish(illustId = 202L, entryPage = 0, page = 5)

        val received = mutableListOf<ViewerPageLink.Ping>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ViewerPageLink.pings.collect { received += it }
        }

        assertEquals(emptyList<ViewerPageLink.Ping>(), received)
    }

    @Test
    fun `viewport keeps only the latest rect`() {
        ViewerPageLink.publishViewport(303L, page = 1, screenRect = intArrayOf(0, 0, 10, 10))
        ViewerPageLink.publishViewport(303L, page = 2, screenRect = intArrayOf(1, 2, 3, 4))

        val latest = ViewerPageLink.viewport.value
        assertEquals(303L, latest?.illustId)
        assertEquals(2, latest?.page)
        assertEquals(listOf(1, 2, 3, 4), latest?.screenRect?.toList())
    }

    @Test
    fun `viewport carries a null rect while the cell is not laid out yet`() {
        // 详情页还没排到那一格时给 null —— 大图据此退回进场矩形，而不是缩向一个算错的位置。
        ViewerPageLink.publishViewport(404L, page = 7, screenRect = null)

        val latest = ViewerPageLink.viewport.value
        assertEquals(404L, latest?.illustId)
        assertEquals(7, latest?.page)
        assertNull(latest?.screenRect)
    }
}
