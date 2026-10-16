package ceui.pixiv.feeds

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.helper.StaggeredManager
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 卡高按 id 走 80 / 140 / 200 三档：两列的高度节奏必须不同，任何一列带上残留行都会露出来。 */
private fun heightOf(id: Int): Int = 80 + (id % 3) * 60

/**
 * 瀑布流换代后，两列首卡必须都贴在列表顶部（不许任何一列带"上一代残留的行"）。
 *
 * 为什么单独测这件事：
 * SGLM 各列的"行"（`Span.mCachedStart` / `Span.mCachedEnd`）只在 `onLayoutChildren` 里、
 * **且 `getChildCount() > 0`** 时才被重写。一旦某个布局把 children 全摘掉却没清行缓存
 * （布局被 `:app` 的 `StaggeredManager` 吞掉 `IndexOutOfBoundsException` 中止，或"空的一代"），
 * 下一趟布局就会拿残留行当这一列的起排 y —— 用户看到的是"某一列顶部空出一块"，左右不定、高度不定。
 *
 * 用例只断言**用户可见的不变量**，不碰 SGLM 私有字段：位置 0 / 位置 1 两张卡必须都存在，
 * 且 `getDecoratedTop - paddingTop == 0`（两列首卡在列表顶部同高）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class StaggeredColumnGapTest {
    private lateinit var activity: ActivityController<FragmentActivity>
    private lateinit var fragment: StaggeredFeedFragment

    @After
    fun tearDown() {
        if (::activity.isInitialized) activity.pause().stop().destroy()
    }

    /**
     * 契约守卫：清空重填 + 回顶（`commitNewGeneration` → `resetToTop`）之后，两列都得从列表顶部起排。
     *
     * 前置刻意造成"回顶时第 0 项还 attached、但已滑出顶部"——那正是 SGLM 会走
     * `updateAnchorFromPendingData` 的「目标可见」分支（`mOffset` 为正、且不置
     * `mInvalidateOffsets`）的情形，也是回顶路径唯一可能把残留行带进新一代的入口。
     */
    @Test
    fun `清空重填回顶后,两列首卡都贴顶`() {
        open()

        fragment.list.scrollBy(0, 30)
        layout()
        val first = fragment.manager.findViewByPosition(0)
        assertNotNull("前置：第 0 项必须还在屏上（回顶才会走「目标可见」分支）", first)
        assertTrue(
            "前置：第 0 项必须已滑出顶部",
            fragment.manager.getDecoratedTop(first!!) < fragment.list.paddingTop,
        )

        // 与上一代完全不重合 → needsCleanSwapAcrossGenerations 为真 → 清空重填 + 回顶
        refreshWith(rows(101..112))
        assertBothColumnsFlushWithTop()
    }

    /**
     * 回归用例：这条**曾经必红**（实测：位置 0 那张卡根本没排出来，第一列整列空着）。
     * 现在由 [StaggeredManager] 在"children 全空"的那趟布局里把残留列行清零，必须保持绿。
     *
     * 复现的是与"布局被中止"**完全相同的状态**：children 被 `removeAndRecycleAllViews` 全摘掉，
     * 而各列的行缓存一个字节都没清（SGLM 只在 `getChildCount() > 0` 时才重写它们）。
     * 触发原因不同（这里是"空的一代 + 待处理滚动目标"），但下一趟布局看到的状态一模一样 ——
     * 实测空布局后两列的末行仍是 240，数据回来后 `fill` 就拿 240 当这一列的起排 y。
     *
     * 三步都不能少：
     * 1. 先滚一段，让各列的末行缓存变成非零；
     * 2. 留一个待处理滚动目标，并**抢在它被消费之前**把 adapter 清空。这里必须用
     *    `submitList(null)`（同步快路）：`submitList(emptyList())` 走的是后台 diff，
     *    adapter 不会立刻变空，中间会插进一趟"待处理目标 + 旧数据"的布局，SGLM 走
     *    `mInvalidateOffsets` 分支把列行清干净，就复现不出来了；
     * 3. 数据回来时用**同一批**（走 DiffUtil 分支，框架不回顶、也不清残留），空洞才留在屏幕上。
     */
    @Test
    fun `空的一代之后,两列仍都贴顶`() {
        open()

        fragment.list.scrollBy(0, 200)
        layout()
        assertTrue("前置：必须真的滚动过，列行缓存才会变成非零", fragment.list.canScrollVertically(-1))

        // ② 待处理滚动目标 + 抢在它被消费前清空 adapter（不经 render，免得中间插进带数据的布局）
        fragment.manager.scrollToPosition(0)
        fragment.adapter!!.submitList(null)
        layout()
        assertEquals("前置：children 必须已被全摘掉", 0, fragment.list.childCount)

        // ③ 同一批数据回来 → 框架走 DiffUtil 分支（不回顶、也不清残留）
        refreshWith(rows(1..12))
        assertBothColumnsFlushWithTop()
    }

    private fun open() {
        activity = Robolectric.buildActivity(FragmentActivity::class.java)
        activity.get().setTheme(R.style.AppTheme)
        activity.setup()
        fragment = StaggeredFeedFragment().apply {
            source.results.trySend(Result.success(page(rows(1..12))))
        }
        activity.get().supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment).commitNow()
        awaitCommit { it.hasLoadedOnce }
        assertBothColumnsFlushWithTop()
    }

    private fun refreshWith(items: List<FeedItem>) {
        val previousGeneration = fragment.committed!!.refreshGeneration
        fragment.model.refresh()
        fragment.source.results.trySend(Result.success(page(items)))
        awaitCommit { it.refreshGeneration > previousGeneration }
    }

    /** 用户可见的不变量：两列首卡都在列表顶部同高，且都真的排出来了。 */
    private fun assertBothColumnsFlushWithTop() {
        val manager = fragment.manager
        val paddingTop = fragment.list.paddingTop
        val firstSpan = manager.findViewByPosition(0)
        val secondSpan = manager.findViewByPosition(1)
        assertNotNull("位置 0 的卡必须排出来（第一列不能整列空着）", firstSpan)
        assertNotNull("位置 1 的卡必须排出来（第二列不能整列空着）", secondSpan)
        assertEquals("位置 0 的卡必须贴顶", 0, manager.getDecoratedTop(firstSpan!!) - paddingTop)
        assertEquals("位置 1 的卡必须贴顶", 0, manager.getDecoratedTop(secondSpan!!) - paddingTop)
    }

    private fun awaitCommit(predicate: (FeedUiState) -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (fragment.committed?.let(predicate) != true) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) { "ListAdapter commit timed out: ${fragment.committed}" }
            Thread.sleep(10)
        }
        layout()
    }

    private fun layout() {
        shadowOf(Looper.getMainLooper()).idle()
        fragment.requireView().apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 640, 240)
        }
    }

    private fun rows(ids: IntRange): List<FeedItem> = ids.map { Row(it) }
    private fun page(items: List<FeedItem>) = FeedPage<String>(items, null)

    data class Row(val id: Int, val label: String = id.toString()) : FeedItem {
        override val feedKey: Any get() = id
    }

    class RowBinding(private val text: TextView) : ViewBinding {
        override fun getRoot(): TextView = text
    }

    class QueueSource : FeedSource<String> {
        val results = Channel<Result<FeedPage<String>>>(Channel.UNLIMITED)
        override suspend fun load(cursor: String?): FeedPage<String> = results.receive().getOrThrow()
    }

    /** 两列瀑布流；卡高按 id 变化，两列节奏不同，残留行一定会露出来。 */
    class StaggeredFeedFragment : FeedFragment() {
        val source = QueueSource()
        var committed: FeedUiState? = null
        override val feedViewModel by feedViewModels { source }
        val model get() = feedViewModel
        val list get() = feedBinding.feedListView
        val manager get() = list.layoutManager as StaggeredGridLayoutManager
        val adapter get() = feedAdapter
        override val loadMoreEnabled = false
        override val refreshEnabled = false
        // 与真实瀑布流页同款（IllustFeedFragment.onCreateLayoutManager 也走它），
        // 残留清理就落在 StaggeredManager 里，用裸 SGLM 测不到。
        override fun onCreateLayoutManager() = StaggeredManager(2, RecyclerView.VERTICAL)
        override fun onListReady(listView: RecyclerView) {
            listView.setPadding(20, 20, 8, 8)
            listView.itemAnimator = null
        }
        override fun onCreateRenderers() = listOf(
            feedRenderer<Row, RowBinding>(inflate = { _, parent, _ ->
                RowBinding(TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(300, 100)
                })
            }) { cell ->
                cell.binding.root.apply {
                    text = cell.item.label
                    layoutParams = RecyclerView.LayoutParams(300, heightOf(cell.item.id))
                }
            },
        )
        override fun onListCommitted(state: FeedUiState) { committed = state }
        override fun onRefreshFailedWithContent(throwable: Throwable) = Unit
    }
}