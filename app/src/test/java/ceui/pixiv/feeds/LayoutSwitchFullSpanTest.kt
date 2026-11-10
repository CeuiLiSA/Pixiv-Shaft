package ceui.pixiv.feeds

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import ceui.lisa.helper.StaggeredManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LayoutSwitchFullSpanTest {
    @Test
    fun `复用排行榜头从齐行切到瀑布流仍占满整行`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val list = RecyclerView(controller.get())
            controller.get().setContentView(list)
            val adapter = FeedAdapter(listOf(
                feedRenderer<Header, CellBinding>(inflate = ::cell, fullSpan = true) { },
                feedRenderer<Work, CellBinding>(inflate = ::cell) { },
            ))
            list.layoutManager = GridLayoutManager(controller.get(), 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = adapter.spanSizeAt(position, 2)
                }
            }
            list.adapter = adapter
            adapter.submitList(listOf(Header, Work(1), Work(2)))
            layout(list)
            val oldHeader = list.findViewHolderForAdapterPosition(0)
            requireNotNull(oldHeader)
            assertTrue(oldHeader.itemView.layoutParams is GridLayoutManager.LayoutParams)

            val manager = StaggeredManager(2, RecyclerView.VERTICAL)
            list.layoutManager = manager
            layout(list)
            val header = list.findViewHolderForAdapterPosition(0)
            requireNotNull(header)
            assertSame("必须复用旧头部 holder，才能覆盖旧 LayoutParams 的转换路径", oldHeader, header)
            assertTrue((header.itemView.layoutParams as StaggeredGridLayoutManager.LayoutParams).isFullSpan)
            val firstWork = manager.findViewByPosition(1)
            requireNotNull(firstWork)
            assertEquals(manager.getDecoratedBottom(header.itemView), manager.getDecoratedTop(firstWork))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `首屏预建剩下的卡从齐行切到瀑布流不崩`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val list = RecyclerView(controller.get())
            controller.get().setContentView(list)
            val adapter = FeedAdapter(listOf(
                feedRenderer<Header, CellBinding>(inflate = ::cell, fullSpan = true) { },
                feedRenderer<Work, CellBinding>(inflate = ::cell) { },
            ))
            list.layoutManager = GridLayoutManager(controller.get(), 1).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = adapter.spanSizeAt(position, 1)
                }
            }
            list.adapter = adapter
            adapter.submitList(listOf(Header) + (1..12).map(::Work))
            // 同 FeedFragment.prewarmFirstScreen：加载态预建 8 张主力卡塞进 pool。它们从没被
            // RecyclerView 取用过，LayoutParams 上没挂 ViewHolder。
            val workType = requireNotNull(adapter.viewTypeOf(Work::class.java))
            list.recycledViewPool.setMaxRecycledViews(workType, 8)
            adapter.prewarm(list, list.recycledViewPool, workType, 8)
            // 单列首屏只用掉其中 4 张，剩 4 张躺在 pool 底
            layout(list)

            // 换成两列瀑布流：首屏要 8 张卡，回收回来的 4 张不够，会取到预建剩下的那几张
            list.layoutManager = StaggeredManager(2, RecyclerView.VERTICAL)
            layout(list)

            assertTrue(list.findViewHolderForAdapterPosition(8) != null)
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

    private fun cell(inflater: android.view.LayoutInflater, parent: android.view.ViewGroup, attach: Boolean): CellBinding =
        CellBinding(TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(300, 120)
        })

    private class CellBinding(private val view: TextView) : ViewBinding {
        override fun getRoot(): TextView = view
    }

    private data object Header : FeedItem {
        override val feedKey: Any get() = "header"
    }

    private data class Work(val id: Int) : FeedItem {
        override val feedKey: Any get() = id
    }
}
