package ceui.pixiv.feeds

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import kotlinx.coroutines.channels.Channel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercise the real Fragment / ListAdapter / LayoutManager, including asynchronous diff commits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FeedRefreshScrollTest {
    private lateinit var activity: ActivityController<FragmentActivity>
    private lateinit var fragment: TestFeedFragment

    @After
    fun tearDown() {
        if (::activity.isInitialized) activity.pause().stop().destroy()
    }

    @Test
    fun `horizontal refresh exposes prepended articles instead of anchoring the old first card`() {
        open()
        refreshWith(rows(101, 102, 103) + rows(1..12))
        assertAtStart(101)
    }

    @Test
    fun `vertical refresh also exposes prepended items`() {
        open(RecyclerView.VERTICAL)
        refreshWith(rows(101, 102, 103) + rows(1..12))
        assertAtStart(101)
    }

    @Test
    fun `cache to network refresh exposes the new first article`() {
        open(cached = true)
        assertTrue(fragment.committed!!.itemsFromCache)
        fragment.source.results.trySend(Result.success(page(rows(101, 102, 103) + rows(1..12))))
        awaitCommit { !it.itemsFromCache && it.refresh is LoadState.Idle }
        assertAtStart(101)
    }

    @Test
    fun `successful refresh with unchanged items returns a scrolled rail to its start`() {
        open()
        scrollTo(4)
        refreshWith(rows(1..12))
        assertAtStart(1)
    }

    @Test
    fun `a local update superseding the refresh diff still scrolls the committed generation to start`() {
        open()
        fragment.model.refresh()
        fragment.source.results.trySend(Result.success(page(rows(101, 102, 103) + rows(1..12))))
        assertEquals(101, fragment.model.uiState.value.items.first().feedKey)
        // The main looper is paused: the refresh diff cannot commit before this newer submission.
        fragment.model.mutateItems { items ->
            items.map { if (it.feedKey == 5) Row(5, "updated") else it }
        }
        awaitCommit { it.items.any { item -> item is Row && item.label == "updated" } }
        assertAtStart(101)
    }

    @Test
    fun `clean replacement still returns to the start`() {
        open(resetOnRefresh = false)
        scrollTo(4)
        refreshWith(rows(101..112))
        assertAtStart(101)
    }

    @Test
    fun `default background refresh with unchanged data preserves the reading position`() {
        open(resetOnRefresh = false)
        scrollTo(4)
        refreshWith(rows(1..12))
        assertEquals(4, fragment.manager.findFirstVisibleItemPosition())
    }

    @Test
    fun `default background refresh after deletion preserves the reading position`() {
        open(resetOnRefresh = false)
        scrollTo(4)
        refreshWith(rows(1..12).filterNot { it.feedKey == 10 })
        assertEquals(4, fragment.manager.findFirstVisibleItemPosition())
    }

    @Test
    fun `default background refresh with prepends preserves the visible card`() {
        open(resetOnRefresh = false)
        scrollTo(4)
        refreshWith(rows(101, 102, 103) + rows(1..12))
        assertEquals(7, fragment.manager.findFirstVisibleItemPosition())
        assertEquals("5", (fragment.manager.findViewByPosition(7) as TextView).text.toString())
    }

    @Test
    fun `append and local edits preserve the reading position`() {
        open()
        scrollTo(4)
        fragment.model.appendItems(rows(13, 14))
        awaitCommit { it.items.size == 14 }
        assertEquals(4, fragment.manager.findFirstVisibleItemPosition())
        fragment.model.mutateItems { items ->
            items.map { if (it.feedKey == 5) Row(5, "updated") else it }
        }
        awaitCommit { (it.items[4] as Row).label == "updated" }
        assertEquals(4, fragment.manager.findFirstVisibleItemPosition())
    }

    @Test
    fun `failed refresh preserves the reading position`() {
        open()
        scrollTo(4)
        fragment.model.refresh()
        fragment.source.results.trySend(Result.failure(IllegalStateException("offline")))
        awaitCommit { it.refresh is LoadState.Error }
        assertEquals(4, fragment.manager.findFirstVisibleItemPosition())
    }

    @Test
    fun `recreated view restores its scroll position without treating retained data as a refresh`() {
        open()
        scrollTo(4)
        val manager = activity.get().supportFragmentManager
        manager.beginTransaction().detach(fragment).commitNow()
        fragment.committed = null
        manager.beginTransaction().attach(fragment).commitNow()
        awaitCommit { it.hasLoadedOnce }
        assertEquals(4, fragment.manager.findFirstVisibleItemPosition())
    }

    private fun open(
        orientation: Int = RecyclerView.HORIZONTAL,
        cached: Boolean = false,
        resetOnRefresh: Boolean = true,
    ) {
        activity = Robolectric.buildActivity(FragmentActivity::class.java)
        activity.get().setTheme(R.style.AppTheme)
        activity.setup()
        fragment = (if (resetOnRefresh) ResettingFeedFragment() else TestFeedFragment()).apply {
            this.orientation = orientation
            if (cached) source.cached = page(rows(1..12))
            else source.results.trySend(Result.success(page(rows(1..12))))
        }
        activity.get().supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment).commitNow()
        awaitCommit { it.hasLoadedOnce }
        assertAtStart(1)
    }

    private fun refreshWith(items: List<FeedItem>) {
        val previousGeneration = fragment.committed!!.refreshGeneration
        fragment.model.refresh()
        fragment.source.results.trySend(Result.success(page(items)))
        awaitCommit { it.refreshGeneration > previousGeneration }
    }

    private fun scrollTo(position: Int) {
        fragment.manager.scrollToPositionWithOffset(position, 0)
        layout()
        assertEquals(position, fragment.manager.findFirstVisibleItemPosition())
    }

    private fun assertAtStart(id: Int) {
        assertEquals("first visible adapter position", 0, fragment.manager.findFirstVisibleItemPosition())
        val first = fragment.manager.findViewByPosition(0)!!
        val offset = if (fragment.orientation == RecyclerView.HORIZONTAL) {
            fragment.manager.getDecoratedLeft(first) - fragment.list.paddingLeft
        } else {
            fragment.manager.getDecoratedTop(first) - fragment.list.paddingTop
        }
        assertEquals("first card must align with the start padding", 0, offset)
        assertEquals(id.toString(), (first as TextView).text.toString())
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
    private fun rows(vararg ids: Int): List<FeedItem> = ids.map { Row(it) }
    private fun page(items: List<FeedItem>) = FeedPage<String>(items, null)

    data class Row(val id: Int, val label: String = id.toString()) : FeedItem {
        override val feedKey: Any get() = id
    }

    class RowBinding(private val text: TextView) : ViewBinding {
        override fun getRoot(): TextView = text
    }

    class QueueSource : FeedSource<String> {
        val results = Channel<Result<FeedPage<String>>>(Channel.UNLIMITED)
        var cached: FeedPage<String>? = null
        override suspend fun loadFromCache(): FeedPage<String>? = cached
        override suspend fun load(cursor: String?): FeedPage<String> = results.receive().getOrThrow()
    }

    class ResettingFeedFragment : TestFeedFragment() {
        override val resetScrollOnRefresh = true
    }

    open class TestFeedFragment : FeedFragment() {
        val source = QueueSource()
        var orientation = RecyclerView.HORIZONTAL
        var committed: FeedUiState? = null
        override val feedViewModel by feedViewModels { source }
        val model get() = feedViewModel
        val list get() = feedBinding.feedListView
        val manager get() = list.layoutManager as LinearLayoutManager
        override val loadMoreEnabled = false
        override val refreshEnabled = false
        override fun onCreateLayoutManager() = LinearLayoutManager(requireContext(), orientation, false)
        override fun onListReady(listView: RecyclerView) {
            listView.setPadding(20, 20, 8, 8)
            listView.itemAnimator = null
        }
        override fun onCreateRenderers() = listOf(
            feedRenderer<Row, RowBinding>(inflate = { _, parent, _ ->
                RowBinding(TextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(240, 100)
                })
            }) { cell -> cell.binding.root.text = cell.item.label },
        )
        override fun onListCommitted(state: FeedUiState) { committed = state }
        override fun onRefreshFailedWithContent(throwable: Throwable) = Unit
    }
}
