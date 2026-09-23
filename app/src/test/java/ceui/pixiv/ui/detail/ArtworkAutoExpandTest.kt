package ceui.pixiv.ui.detail

import android.os.Looper
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import ceui.pixiv.api.model.Illust
import ceui.pixiv.feeds.FeedAdapter
import ceui.pixiv.snapshot.SnapshotManifest
import ceui.pixiv.snapshot.SnapshotRuntimeCache
import ceui.pixiv.snapshot.SnapshotViewerData
import com.blankj.utilcode.util.Utils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ArtworkAutoExpandTest.TestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ArtworkAutoExpandTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    private lateinit var host: ActivityController<FragmentActivity>
    private lateinit var fragment: ArtworkV3Fragment
    private val snapshotId = "auto-expand-test"

    @Before
    fun setUp() {
        host = Robolectric.buildActivity(FragmentActivity::class.java)
        host.get().setTheme(R.style.AppTheme)
        Utils.init(host.get().application)
        Shaft.sSettings = Settings()
        host.setup()
    }

    @After
    fun tearDown() {
        host.pause().stop().destroy()
        SnapshotRuntimeCache.remove(snapshotId)
    }

    @Test
    fun `expanded pages retain a working collapse action after view recreation without image binds`() {
        open(autoExpand = true)
        awaitPages(3)
        layout()
        assertTrue((fragment.ensurePageAdapter() as CollapsibleIllustAdapter).isExpanded)
        (list.layoutManager as StaggeredGridLayoutManager).scrollToPositionWithOffset(
            (list.adapter as FeedAdapter).currentList.indexOfFirst { it is ArtworkCommentsItem }, 0,
        )
        layout()
        assertNoVisiblePages()
        val manager = host.get().supportFragmentManager
        manager.beginTransaction().detach(fragment).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(fragment.view)
        manager.beginTransaction().attach(fragment).commitNow()
        awaitPages(3)
        layout()

        assertNoVisiblePages()
        val pill = fragment.requireView().findViewById<View>(R.id.collapse_pill)
        assertEquals("collapse must not depend on rebinding an offscreen image", View.VISIBLE, pill.visibility)
        assertTrue(pill.performClick())
        awaitPages(1)
        assertTrue((fragment.ensurePageAdapter() as CollapsibleIllustAdapter).isCollapsed)
    }

    @Test
    fun `disabled preference keeps three page works collapsed`() {
        open(autoExpand = false)
        awaitPages(1)
        assertTrue((fragment.ensurePageAdapter() as CollapsibleIllustAdapter).isCollapsed)
        assertEquals(View.GONE, fragment.requireView().findViewById<View>(R.id.collapse_pill).visibility)
    }

    @Test
    fun `two page works remain fully visible without a collapse action`() {
        open(autoExpand = true, pageCount = 2)
        awaitPages(2)
        assertFalse(fragment.ensurePageAdapter() is CollapsibleIllustAdapter)
        assertEquals(View.GONE, fragment.requireView().findViewById<View>(R.id.collapse_pill).visibility)
    }

    private val list: RecyclerView
        get() = fragment.requireView().findViewById(ceui.pixiv.feeds.R.id.feed_list_view)

    private fun layout() {
        shadowOf(Looper.getMainLooper()).idle()
        fragment.requireView().apply {
            measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            layout(0, 0, 640, 480)
        }
    }

    private fun assertNoVisiblePages() {
        val items = (list.adapter as FeedAdapter).currentList
        for (index in 0 until list.childCount) {
            val position = list.getChildAdapterPosition(list.getChildAt(index))
            assertFalse("image still visible at $position", items.getOrNull(position) is ArtworkPageItem)
        }
    }

    private fun open(autoExpand: Boolean, pageCount: Int = 3) {
        Shaft.sSettings.isArtworkV3AutoExpandMultiPage = autoExpand
        SnapshotRuntimeCache.put(snapshotId, SnapshotViewerData(
            snapshotDir = host.get().cacheDir,
            manifest = SnapshotManifest(snapshotId = snapshotId, illustId = 1L),
            illust = Illust(id = 1L, type = "illust", page_count = pageCount,
                title = "Snapshot", create_date = "2026-09-22T12:00:00+09:00"),
            assets = emptyMap(),
            comments = null,
        ))
        fragment = ArtworkV3Fragment.newInstanceSnapshot(snapshotId)
        host.get().supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment).commitNow()
    }

    private fun awaitPages(count: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if ((list.adapter as FeedAdapter).currentList.count { it is ArtworkPageItem } == count) return
            check(System.nanoTime() < deadline) { "page list did not commit" }
            Thread.sleep(10)
        }
    }
}
