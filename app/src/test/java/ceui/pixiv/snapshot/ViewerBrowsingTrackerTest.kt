package ceui.pixiv.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ViewerBrowsingTracker] 的纯 JVM 测试。
 *
 * 钉住三件事：按页累计驻留、挂起期间不计入、每页「是否缩放过」互相独立。
 */
class ViewerBrowsingTrackerTest {

    @Test
    fun `dwell accumulates per page across page changes`() {
        val tracker = ViewerBrowsingTracker(pageCount = 3)
        tracker.resume(0L)
        tracker.onPageVisible(0, 0L)

        tracker.onPageVisible(1, 10_000L)
        tracker.onPageVisible(2, 25_000L)
        val pages = tracker.finish(40_000L, at = 1_000L)

        assertEquals(listOf(0, 1, 2), pages.map { it.page })
        assertEquals(listOf(10_000L, 15_000L, 15_000L), pages.map { it.ms })
        assertEquals(1_000L, pages.first().at)
    }

    @Test
    fun `time spent suspended does not count towards any page`() {
        val tracker = ViewerBrowsingTracker(pageCount = 2)
        tracker.resume(0L)
        tracker.onPageVisible(0, 0L)

        tracker.suspend(10_000L)
        tracker.resume(70_000L)
        val pages = tracker.finish(90_000L, at = 5L)

        // 第一段 10s + 第二段 20s；挂起中的 60s 不计入。
        assertEquals(listOf(30_000L), pages.map { it.ms })
    }

    @Test
    fun `zoomed flag is per page and idempotent`() {
        val tracker = ViewerBrowsingTracker(pageCount = 2)
        tracker.resume(0L)
        tracker.onPageVisible(0, 0L)
        tracker.onPageZoomed(0)
        tracker.onPageZoomed(0)
        tracker.onPageVisible(1, 5_000L)
        val pages = tracker.finish(10_000L, at = 0L)

        assertTrue(pages.first { it.page == 0 }.zoomed)
        assertFalse(pages.first { it.page == 1 }.zoomed)
    }

    @Test
    fun `pages with zero dwell are dropped and out-of-range pages are ignored`() {
        val tracker = ViewerBrowsingTracker(pageCount = 1)
        tracker.resume(0L)
        tracker.onPageVisible(0, 0L)
        tracker.onPageVisible(1, 5_000L) // 越界页：只把 0 号的 5s 结掉
        val pages = tracker.finish(5_000L, at = 0L)

        assertEquals(listOf(0), pages.map { it.page })
        assertEquals(listOf(5_000L), pages.map { it.ms })
    }

    @Test
    fun `re-selecting the same page is a no-op`() {
        val tracker = ViewerBrowsingTracker(pageCount = 2)
        tracker.resume(0L)
        tracker.onPageVisible(0, 0L)
        tracker.onPageVisible(0, 3_000L)
        tracker.onPageVisible(0, 7_000L)
        val pages = tracker.finish(10_000L, at = 0L)

        assertEquals(listOf(10_000L), pages.map { it.ms })
    }

    @Test
    fun `never-resumed tracker reports nothing`() {
        val tracker = ViewerBrowsingTracker(pageCount = 1)
        tracker.onPageVisible(0, 0L)
        val pages = tracker.finish(5_000L, at = 0L)

        // 从没 resume = 宿主从没可见，任何页都不该有驻留。
        assertTrue(pages.isEmpty())
    }
}