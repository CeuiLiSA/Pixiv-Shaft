package ceui.pixiv.ui.detail

import android.os.Looper
import android.view.View
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.room.Room
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.Settings
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.MetaPage
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.feeds.FeedAdapter
import ceui.pixiv.snapshot.SnapshotManifest
import ceui.pixiv.snapshot.SnapshotRuntimeCache
import ceui.pixiv.snapshot.SnapshotViewerData
import ceui.pixiv.utils.NetworkStateManager
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], application = ArtworkAutoExpandTest.TestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ArtworkAutoExpandTest {
    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    private lateinit var host: ActivityController<FragmentActivity>
    private lateinit var fragment: ArtworkV3Fragment
    private var database: AppDatabase? = null
    private var network: NetworkStateManager? = null
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
        network?.unregisterNetworkCallback()
        AppDatabase.destroyInstance()
        database?.close()
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

    @Test
    fun `loading originals during a pending collapse keeps the new adapter collapsed`() {
        openOnline(autoExpand = true)
        awaitPages(3)
        val oldAdapter = fragment.ensurePageAdapter() as CollapsibleIllustAdapter
        oldAdapter.collapse()
        assertTrue(oldAdapter.isCollapsed)
        // The old three-page list remains displayed until AsyncListDiffer commits the collapse.
        assertEquals(3, (list.adapter as FeedAdapter).currentList.count { it is ArtworkPageItem })
        ReflectionHelpers.callInstanceMethod<Unit>(fragment, "applyForceOriginal")
        awaitPages(1)
        val replacement = fragment.ensurePageAdapter() as CollapsibleIllustAdapter
        assertNotSame(oldAdapter, replacement)
        assertTrue("original-image mode must preserve the latest collapse action", replacement.isCollapsed)
        layout()
        val cover = list.findViewHolderForAdapterPosition(0)!!.itemView
        assertEquals(View.VISIBLE, cover.findViewById<View>(R.id.expand_overlay).visibility)
    }

    @Test
    fun `enabling auto expand during a visit leaves the retained collapsed list expandable`() {
        openOnline(autoExpand = false)
        awaitPages(1)
        Shaft.sSettings.isArtworkV3AutoExpandMultiPage = true
        recreateView()
        awaitPages(1)
        val adapter = fragment.ensurePageAdapter() as CollapsibleIllustAdapter
        assertTrue(adapter.isCollapsed)
        adapter.expand()
        awaitPages(3)
    }

    @Test
    fun `disabling auto expand before view recreation retains the default folding behavior`() {
        openOnline(autoExpand = true)
        awaitPages(3)
        Shaft.sSettings.isArtworkV3AutoExpandMultiPage = false
        recreateView()
        awaitPages(1)
        assertTrue((fragment.ensurePageAdapter() as CollapsibleIllustAdapter).isCollapsed)
    }

    private fun recreateView() {
        val manager = host.get().supportFragmentManager
        manager.beginTransaction().detach(fragment).commitNow()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(fragment.view)
        manager.beginTransaction().attach(fragment).commitNow()
    }

    private fun openOnline(autoExpand: Boolean) {
        val app = host.get().application
        Shaft.sGson = Gson()
        Shaft.sPreferences = app.getSharedPreferences("artwork-test", 0)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", database)
        network = NetworkStateManager(app)
        ReflectionHelpers.setField(app, "networkStateManager", network)
        Shaft.sSettings.isArtworkV3AutoExpandMultiPage = autoExpand
        ObjectPool.update(Illust(
            id = 1L, type = "illust", page_count = 3, width = 1200, height = 1800,
            title = "Artwork", create_date = "2026-09-22T12:00:00+09:00",
            meta_pages = List(3) { MetaPage() },
        ), isFullVersion = true)
        fragment = ArtworkV3Fragment.newInstance(1L)
        // STARTED exercises the real online detail UI and source without onResume's network probes.
        host.get().supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .setMaxLifecycle(fragment, Lifecycle.State.STARTED).commitNow()
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
