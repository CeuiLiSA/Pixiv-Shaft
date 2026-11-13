package ceui.pixiv.ui.home

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
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
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IndexedMasonryLayoutManagerTest {
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

    private fun layout(list: RecyclerView) {
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