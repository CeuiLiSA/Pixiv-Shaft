package ceui.pixiv.ui.home

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewbinding.ViewBinding
import ceui.pixiv.feeds.FeedAdapter
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.feedRenderer
import ceui.lisa.helper.StaggeredManager
import ceui.pixiv.ui.common.JustifiedItemDecoration
import ceui.pixiv.ui.common.JustifiedLayoutManager
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IndexedMasonryLayoutManagerTest {
    @Test
    fun invalidRestoredPositionDoesNotJumpWhenLaterPagesArrive() = withIndexedList { list, adapter, manager ->
        submit(adapter, (1..60).map(::Work))
        layout(list)
        manager.scrollToFeedPositionWithOffset(28, -17)
        layout(list)
        val saved = manager.onSaveInstanceState()

        adapter.submitList(null)
        val restored = IndexedMasonryLayoutManager(2, 8) { _, width -> width }
        list.layoutManager = restored
        restored.onRestoreInstanceState(saved)
        layout(list)
        submit(adapter, (1..8).map(::Work))
        layout(list)
        list.scrollBy(0, 120)
        val offset = list.computeVerticalScrollOffset()
        assertEquals(120, offset)

        submit(adapter, (1..60).map(::Work))
        layout(list)
        assertEquals(offset, list.computeVerticalScrollOffset())
    }

    @Test
    fun emptyLayoutRetainsAValidPendingRestoration() = withIndexedList { list, adapter, manager ->
        submit(adapter, (1..60).map(::Work))
        layout(list)
        manager.scrollToFeedPositionWithOffset(28, -17)
        layout(list)
        val saved = manager.onSaveInstanceState()

        adapter.submitList(null)
        val restored = IndexedMasonryLayoutManager(2, 8) { _, width -> width }
        list.layoutManager = restored
        restored.onRestoreInstanceState(saved)
        layout(list)
        submit(adapter, (1..60).map(::Work))
        layout(list)
        assertEquals(-17, list.findViewHolderForAdapterPosition(28)!!.itemView.top)
    }

    @Test
    fun insertionAndRemovalAboveTheViewportKeepTheSameWorkInPlace() = withIndexedList { list, adapter, manager ->
        submit(adapter, (1..60).map(::Work))
        layout(list)
        manager.scrollToFeedPositionWithOffset(28, -17)
        layout(list)
        val anchor = list.findViewHolderForAdapterPosition(28)!!.itemView
        val top = anchor.top

        submit(adapter, (101..104).map(::Work) + (1..60).map(::Work))
        layout(list)
        assertSame(anchor, list.findViewHolderForAdapterPosition(32)!!.itemView)
        assertEquals(top, anchor.top)
        submit(adapter, (1..60).map(::Work))
        layout(list)
        assertSame(anchor, list.findViewHolderForAdapterPosition(28)!!.itemView)
        assertEquals(top, anchor.top)
    }

    private fun withIndexedList(block: (RecyclerView, FeedAdapter, IndexedMasonryLayoutManager) -> Unit) {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val list = RecyclerView(controller.get())
            controller.get().setContentView(list)
            val adapter = FeedAdapter(listOf(
                feedRenderer<Work, WidthCellBinding>(inflate = ::widthCell) { },
            ))
            val manager = IndexedMasonryLayoutManager(2, 8) { _, width -> width }
            list.layoutManager = manager
            list.adapter = adapter
            block(list, adapter, manager)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun submit(adapter: FeedAdapter, items: List<FeedItem>) {
        var committed = false
        adapter.submitList(items) { committed = true }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!committed) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "ListAdapter commit timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun scrollBoundsAreReportedToRefreshAndAccessibility() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val refresh = SwipeRefreshLayout(controller.get())
            val list = RecyclerView(controller.get())
            refresh.addView(list)
            controller.get().setContentView(refresh)
            list.setPadding(0, 24, 0, 64)
            val adapter = FeedAdapter(listOf(
                feedRenderer<Work, WidthCellBinding>(inflate = ::widthCell) { },
            ))
            list.layoutManager = IndexedMasonryLayoutManager(2, 8) { _, width -> width }
            list.adapter = adapter
            adapter.submitList((1..60).map(::Work))
            layout(refresh)

            assertFalse(list.canScrollVertically(-1))
            assertTrue(list.canScrollVertically(1))
            assertFalse(refresh.canChildScrollUp())
            list.scrollBy(0, 250)
            assertTrue(list.canScrollVertically(-1))
            assertTrue(list.canScrollVertically(1))
            assertTrue(refresh.canChildScrollUp())
            assertEquals(250, list.computeVerticalScrollOffset())
            assertEquals(412, list.computeVerticalScrollExtent())

            val info = AccessibilityNodeInfoCompat.obtain()
            list.compatAccessibilityDelegate!!.onInitializeAccessibilityNodeInfo(list, info)
            assertTrue(info.isScrollable)
            assertTrue(info.actionList.contains(
                AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD
            ))

            list.scrollBy(0, Int.MAX_VALUE)
            assertTrue(list.canScrollVertically(-1))
            assertFalse(list.canScrollVertically(1))
            assertEquals(
                list.computeVerticalScrollRange() - list.computeVerticalScrollExtent(),
                list.computeVerticalScrollOffset(),
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun shortAndEmptyListsDoNotReportScrollableContent() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val list = RecyclerView(controller.get())
            controller.get().setContentView(list)
            val adapter = FeedAdapter(listOf(
                feedRenderer<Work, WidthCellBinding>(inflate = ::widthCell) { },
            ))
            list.layoutManager = IndexedMasonryLayoutManager(2, 8) { _, width -> width }
            list.adapter = adapter
            adapter.submitList(listOf(Work(1)))
            layout(list)
            assertFalse(list.canScrollVertically(-1))
            assertFalse(list.canScrollVertically(1))
            assertEquals(0, list.computeVerticalScrollOffset())
            adapter.submitList(null)
            layout(list)
            assertEquals(0, list.computeVerticalScrollRange())
            assertEquals(0, list.computeVerticalScrollExtent())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun unclippedPaddingKeepsCardsVisibleUntilTheyLeaveTheView() {
        assertPaddingVisibility(clipToPadding = false)
    }

    @Test
    fun clippedPaddingOnlyKeepsCardsInTheContentArea() {
        assertPaddingVisibility(clipToPadding = true)
    }

    private fun assertPaddingVisibility(clipToPadding: Boolean) {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val list = RecyclerView(controller.get())
            controller.get().setContentView(list)
            list.clipToPadding = clipToPadding
            list.setPadding(0, 100, 0, 100)
            val adapter = FeedAdapter(listOf(
                feedRenderer<Work, WidthCellBinding>(inflate = ::widthCell) { },
            ))
            list.layoutManager = IndexedMasonryLayoutManager(2, 8) { _, width -> width }
            list.adapter = adapter
            adapter.submitList((1..60).map(::Work))
            layout(list)
            // The second row starts at y=404, inside the bottom padding of this 500px view.
            assertEquals(!clipToPadding, list.findViewHolderForAdapterPosition(2) != null)
            list.scrollBy(0, 350)
            // The first row still intersects the top padding, ending at y=46.
            assertEquals(!clipToPadding, list.findViewHolderForAdapterPosition(0) != null)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun deepJumpThenScrollToHeaderKeepsBothFirstCardsAligned() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val list = RecyclerView(controller.get())
            controller.get().setContentView(list)
            val adapter = FeedAdapter(
                listOf(
                    feedRenderer<Header, CellBinding>(inflate = ::cell, fullSpan = true) { },
                    feedRenderer<Work, CellBinding>(inflate = ::cell) { },
                )
            )
            val manager = IndexedMasonryLayoutManager(2, 8) { item, _ ->
                if (item is Work) 100 else 200
            }
            list.layoutManager = manager
            list.adapter = adapter
            adapter.submitList(listOf(Header) + (1..60).map(::Work))
            layout(list)
            manager.scrollToFeedPositionWithOffset(28, 30)
            layout(list)
            assertNotNull(list.findViewHolderForAdapterPosition(28))
            assertEquals(30, list.findViewHolderForAdapterPosition(28)!!.itemView.top)
            repeat(40) { list.scrollBy(0, -100) }
            layout(list)
            val header = list.findViewHolderForAdapterPosition(0)!!.itemView
            val left = list.findViewHolderForAdapterPosition(1)!!.itemView
            val right = list.findViewHolderForAdapterPosition(2)!!.itemView
            assertEquals(header.bottom + 16, left.top)
            assertEquals(left.top, right.top)
            assertTrue(left.left < right.left)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun variableHeightCardsStillReturnToACompleteFirstRow() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val list = RecyclerView(controller.get())
            controller.get().setContentView(list)
            val adapter = FeedAdapter(
                listOf(
                    feedRenderer<Header, CellBinding>(inflate = ::cell, fullSpan = true) { },
                    feedRenderer<Work, CellBinding>(inflate = ::cell) { cell ->
                        cell.itemView.layoutParams.height = workHeight(cell.item.id)
                    },
                )
            )
            list.layoutManager = IndexedMasonryLayoutManager(2, 8) { item, _ ->
                if (item is Work) workHeight(item.id) else 100
            }
            list.adapter = adapter
            adapter.submitList(listOf(Header) + (1..60).map(::Work))
            layout(list)
            list.layoutManager!!.scrollToPosition(28)
            layout(list)
            repeat(50) { list.scrollBy(0, -100) }
            layout(list)
            val header = list.findViewHolderForAdapterPosition(0)!!.itemView
            val first = list.findViewHolderForAdapterPosition(1)!!.itemView
            val second = list.findViewHolderForAdapterPosition(2)!!.itemView
            assertEquals(header.bottom + 16, first.top)
            assertEquals(first.top, second.top)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun workHeight(id: Int): Int = 60 + id % 5 * 40

    @Test
    fun switchingWhileHeaderVisibleReusesItsHolder() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val list = RecyclerView(controller.get())
            controller.get().setContentView(list)
            val adapter = FeedAdapter(
                listOf(
                    feedRenderer<Header, CellBinding>(inflate = ::cell, fullSpan = true) { },
                    feedRenderer<Work, CellBinding>(inflate = ::cell) { },
                )
            )
            list.layoutManager = StaggeredManager(2, RecyclerView.VERTICAL)
            list.adapter = adapter
            adapter.submitList(listOf(Header, Work(1), Work(2)))
            layout(list)
            val oldHeader = list.findViewHolderForAdapterPosition(0)
            assertNotNull(oldHeader)

            list.layoutManager = IndexedMasonryLayoutManager(2, 8) { item, _ ->
                if (item is Work) 100 else 200
            }
            adapter.notifyItemRangeChanged(1, 2)
            layout(list)
            assertSame(oldHeader, list.findViewHolderForAdapterPosition(0))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun switchingFromRowLayoutWhileHeaderVisibleReusesItsHolder() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val list = RecyclerView(controller.get())
            controller.get().setContentView(list)
            val adapter = FeedAdapter(
                listOf(
                    feedRenderer<Header, CellBinding>(inflate = ::cell, fullSpan = true) { },
                    feedRenderer<Work, CellBinding>(inflate = ::cell) { },
                )
            )
            list.layoutManager = GridLayoutManager(controller.get(), 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = adapter.spanSizeAt(position, 2)
                }
            }
            list.adapter = adapter
            adapter.submitList(listOf(Header, Work(1), Work(2)))
            layout(list)
            val oldHeader = list.findViewHolderForAdapterPosition(0)
            assertNotNull(oldHeader)

            list.layoutManager = IndexedMasonryLayoutManager(2, 8) { item, _ ->
                if (item is Work) 100 else 200
            }
            layout(list)
            assertSame(oldHeader, list.findViewHolderForAdapterPosition(0))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun switchingBackToStaggeredWhileHeaderVisibleReusesItsHolder() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val list = RecyclerView(controller.get())
            controller.get().setContentView(list)
            val adapter = FeedAdapter(
                listOf(
                    feedRenderer<Header, CellBinding>(inflate = ::cell, fullSpan = true) { },
                    feedRenderer<Work, CellBinding>(inflate = ::cell) { },
                )
            )
            list.layoutManager = IndexedMasonryLayoutManager(2, 8) { item, _ ->
                if (item is Work) 100 else 200
            }
            list.adapter = adapter
            adapter.submitList(listOf(Header, Work(1), Work(2)))
            layout(list)
            val oldHeader = list.findViewHolderForAdapterPosition(0)
            assertNotNull(oldHeader)

            list.layoutManager = StaggeredManager(2, RecyclerView.VERTICAL)
            adapter.notifyItemRangeChanged(1, 2)
            layout(list)
            assertSame(oldHeader, list.findViewHolderForAdapterPosition(0))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun switchingFromDecoratedJustifiedRowsKeepsActualCardBounds() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val list = RecyclerView(controller.get())
            controller.get().setContentView(list)
            val adapter = FeedAdapter(
                listOf(
                    feedRenderer<Header, CellBinding>(inflate = ::cell, fullSpan = true) { },
                    feedRenderer<Work, WidthCellBinding>(inflate = ::widthCell) { },
                )
            )
            val rowManager = JustifiedLayoutManager(controller.get(), 2, 8)
            list.layoutManager = rowManager
            list.addItemDecoration(JustifiedItemDecoration(rowManager, edgeHeaderAtTop = true))
            list.adapter = adapter
            adapter.submitList(listOf(Header) + (1..20).map(::Work))
            layout(list)
            list.removeItemDecorationAt(0)
            list.layoutManager = IndexedMasonryLayoutManager(2, 8) { item, width ->
                if (item is Work) width else 200
            }
            adapter.notifyItemRangeChanged(1, 20)
            layout(list)
            val first = list.findViewHolderForAdapterPosition(1)!!.itemView
            val second = list.findViewHolderForAdapterPosition(2)!!.itemView
            val third = list.findViewHolderForAdapterPosition(3)!!.itemView
            assertEquals(first.top, second.top)
            assertEquals(8, third.top - first.bottom)
            for (childIndex in 0 until list.childCount) {
                val child = list.getChildAt(childIndex)
                assertEquals(child.measuredWidth, child.width)
                assertEquals(child.measuredHeight, child.height)
            }
            assertEquals(8, first.left)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun offscreenFooterIsNotBoundWhileBuildingTheIndex() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val footerBinds = AtomicInteger()
            val list = RecyclerView(controller.get())
            controller.get().setContentView(list)
            val adapter = FeedAdapter(
                listOf(
                    feedRenderer<Work, CellBinding>(inflate = ::cell) { },
                    feedRenderer<Footer, CellBinding>(inflate = ::cell, fullSpan = true) {
                        footerBinds.incrementAndGet()
                    },
                )
            )
            list.layoutManager = IndexedMasonryLayoutManager(2, 8) { item, _ ->
                if (item is Work) 100 else 56
            }
            list.adapter = adapter
            adapter.submitList((1..60).map(::Work) + Footer)
            layout(list)
            assertEquals(0, footerBinds.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun layout(list: View) {
        list.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 600, 500)
    }

    private fun cell(
        inflater: android.view.LayoutInflater,
        parent: android.view.ViewGroup,
        attach: Boolean
    ): CellBinding =
        CellBinding(TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(300, 100)
        })

    private fun widthCell(
        inflater: android.view.LayoutInflater,
        parent: android.view.ViewGroup,
        attach: Boolean
    ): WidthCellBinding = WidthCellBinding(object : View(parent.context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = View.MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(width, width)
        }
    }.apply { layoutParams = RecyclerView.LayoutParams(-1, -2) })

    private class WidthCellBinding(private val view: View) : ViewBinding {
        override fun getRoot(): View = view
    }

    private class CellBinding(private val view: TextView) : ViewBinding {
        override fun getRoot(): TextView = view
    }

    private data object Footer : FeedItem {
        override val feedKey: Any get() = "footer"
    }

    private data object Header : FeedItem {
        override val feedKey: Any get() = "header"
    }

    private data class Work(val id: Int) : FeedItem {
        override val feedKey: Any get() = id
    }
}
