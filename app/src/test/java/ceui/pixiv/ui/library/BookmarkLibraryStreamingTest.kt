package ceui.pixiv.ui.library

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelStore
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentBookmarkLibraryBinding
import ceui.lisa.utils.Settings
import ceui.loxia.Novel
import ceui.loxia.Tag
import ceui.loxia.User
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.UserPreview
import ceui.pixiv.db.mirror.*
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.FeedPagingPolicy
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.ui.common.UserFeedItem
import ceui.pixiv.services.ServicesProvider
import ceui.pixiv.utils.NetworkStateManager
import com.google.gson.Gson
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BookmarkLibraryStreamingTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private lateinit var db: AppDatabase
    private val app get() = RuntimeEnvironment.getApplication()
    private var serial = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        Shaft.sSettings = Settings().apply { isR18FilterDefaultEnable = true }
        Shaft.sGson = Gson()
    }

    @After
    fun tearDown() {
        models.clear()
        AppDatabase.destroyInstance()
        db.close()
        Dispatchers.resetMain()
    }

    private fun shelf(type: MirrorContentType = MirrorContentType.ILLUST) =
        BookmarkShelf(123L, type, MirrorRestrict.PUBLIC)

    private fun model(shelf: BookmarkShelf) = BookmarkLibraryViewModel().also {
        models.put("library-${serial++}", it)
        it.bind(shelf)
        it.setMirrorState(state(shelf))
    }

    private fun state(shelf: BookmarkShelf, complete: Boolean = false) = BookmarkMirrorStateEntity(
        shelfKey = shelf.key, ownerUid = shelf.ownerUid, contentType = shelf.contentType.code,
        restrictCode = shelf.restrict.code, phase = if (complete) MirrorPhase.SYNCED else MirrorPhase.BACKFILLING,
        nextUrl = null, generation = 1, nextBackfillSeq = 0L, headSeqCursor = 0L,
        headBlockCeiling = 0L, pagesThisRun = 0, itemsThisRun = 0,
        firstCompletedAt = if (complete) 1L else 0L, lastSyncedAt = 0L, lastFullSweepAt = 0L,
        lastErrorAt = 0L, lastError = null, consecutiveFailures = 0, cooldownUntil = 0L, updatedAt = 0L,
    )

    private fun insert(shelf: BookmarkShelf, ids: IntRange, hidden: Boolean = false) {
        val rows = ids.map { id ->
            val tags = if (hidden) listOf(Tag(name = "R-18")) else emptyList()
            when (shelf.contentType) {
                MirrorContentType.ILLUST -> BookmarkMirrorMapper.fromIllust(
                    shelf, Illust(id = id.toLong(), title = "work $id", tags = tags, visible = true),
                    -id.toLong(), 1, 0L,
                )
                MirrorContentType.NOVEL -> BookmarkMirrorMapper.fromNovel(
                    shelf, Novel(id = id.toLong(), title = "work $id", tags = tags, visible = true),
                    -id.toLong(), 1, 0L,
                )
                MirrorContentType.USER -> BookmarkMirrorMapper.fromUserPreview(
                    shelf,
                    UserPreview(
                        user = User(id = id.toLong(), name = "user $id"),
                        illusts = listOf(Illust(id = id * 10L, title = "work $id", tags = tags, visible = true)),
                    ),
                    -id.toLong(), 1, 0L,
                )
            }.row
        }
        db.bookmarkMirrorDao().insertRows(rows)
    }

    private suspend fun updateCount(library: BookmarkLibraryViewModel, expected: Int) {
        library.onMirrorChanged()
        ReflectionHelpers.getField<Job>(library, "countJob").join()
        assertEquals(expected, library.shelfStats.value?.total)
    }

    private fun ui(library: BookmarkLibraryViewModel, feed: FeedViewModel<String>, atTop: Boolean = true): BookmarkLibraryUi {
        val context = ContextThemeWrapper(app, R.style.AppTheme)
        val binding = FragmentBookmarkLibraryBinding.inflate(LayoutInflater.from(context))
        val list = object : RecyclerView(context) {
            override fun canScrollVertically(direction: Int) = direction > 0 || !atTop
        }
        return BookmarkLibraryUi(Fragment(), binding, list, library, feed, library.shelf.contentType,
            itemCount = { feed.uiState.value.items.size })
    }

    @Test
    fun `following shelf streams user cards through the shared source without work filters`() = runTest(dispatcher) {
        val shelf = shelf(MirrorContentType.USER)
        val library = model(shelf)
        // 预览作品带 R-18 标签：关注书架一行是一个人，作品屏蔽规则不该把人藏掉（对齐原关注列表）
        insert(shelf, 1..70, hidden = true)
        updateCount(library, 70)
        val source = BookmarkLibraryFeedSource(library, shelf.contentType)

        val first = source.load(null)
        assertEquals(60, first.items.size)
        assertTrue(first.items.all { it is UserFeedItem })
        assertEquals((1L..60L).toList(), first.items.map { it.feedKey })
        assertEquals("user 1", (first.items.first() as UserFeedItem).user?.name)

        val second = source.load(first.nextCursor)
        assertEquals((61L..70L).toList(), second.items.map { it.feedKey })
        assertNull(second.nextCursor)
    }

    @Test
    fun `new head bookmark after sync completion is not mistaken for backfill`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        insert(shelf, 1..30)
        val source = BookmarkLibraryFeedSource(library, shelf.contentType)
        updateCount(library, 30)
        source.load(null)
        library.setMirrorState(state(shelf, complete = true))
        insert(shelf, 0..0)
        updateCount(library, 31)
        assertNull("新增表头应走顶部刷新，不能用 offset 30 重读旧尾项", library.growingTailCursor())
    }

    @Test
    fun `new head bookmark with unread old pages must refresh the top`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        library.setMirrorState(state(shelf, complete = true))
        insert(shelf, 1..120)
        updateCount(library, 120)
        val feed = FeedViewModel(BookmarkLibraryFeedSource(library, shelf.contentType), autoLoad = false)
        models.put("feed", feed)
        feed.refresh()
        val first = feed.uiState.first { it.hasLoadedOnce && it.refresh is LoadState.Idle }
        val ui = ui(library, feed)
        insert(shelf, 0..0)
        updateCount(library, 121)
        ui.onListCommitted(first)
        runCurrent()
        val final = feed.uiState.first { it.refresh is LoadState.Idle }
        assertEquals("未读旧页不能掩盖表头新增", 0L, final.items.first().feedKey)
        assertEquals(first.refreshGeneration + 1, final.refreshGeneration)
        ui.destroy()
    }

    @Test
    fun `head arriving during first page load is refreshed when that page commits`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        library.setMirrorState(state(shelf, complete = true))
        insert(shelf, 1..120)
        updateCount(library, 120)
        val source = BookmarkLibraryFeedSource(library, shelf.contentType)
        val queried = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var firstQuery = true
        val delayed = object : FeedSource<String> by source {
            override suspend fun load(cursor: String?) = source.load(cursor).also {
                if (firstQuery) {
                    firstQuery = false
                    queried.complete(Unit)
                    release.await()
                }
            }
        }
        val feed = FeedViewModel(delayed, autoLoad = false)
        models.put("feed", feed)
        val ui = ui(library, feed)
        feed.refresh()
        queried.await()
        insert(shelf, 0..0)
        updateCount(library, 121)
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "refreshIfStale")
        release.complete(Unit)
        val first = feed.uiState.first { it.hasLoadedOnce && it.refresh is LoadState.Idle }
        ui.onListCommitted(first)
        runCurrent()
        val final = feed.uiState.first { it.refresh is LoadState.Idle }
        assertEquals(0L, final.items.first().feedKey)
        assertEquals(first.refreshGeneration + 1, final.refreshGeneration)
        ui.destroy()
    }

    @Test
    fun `unbookmarking consumed rows cannot skip the next streamed tail`() = runTest(dispatcher) {
        for (type in MirrorContentType.entries) {
            val shelf = shelf(type)
            val library = model(shelf)
            insert(shelf, 1..30)
            updateCount(library, 30)
            val source = BookmarkLibraryFeedSource(library, type)
            source.load(null)
            db.bookmarkMirrorDao().deleteTarget(shelf.ownerUid, type.code, 1L)
            insert(shelf, 31..45)
            updateCount(library, 44)
            val cursor = library.growingTailCursor()
            assertNotNull(cursor)
            assertEquals((31L..45L).toList(), source.load(cursor).items.map { it.feedKey })
        }
    }

    @Test
    fun `tail replacement with unchanged count still resumes after cancellations`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        insert(shelf, 1..30)
        updateCount(library, 30)
        val source = BookmarkLibraryFeedSource(library, shelf.contentType)
        source.load(null)
        val changed = async { library.shelfStats.drop(1).first() }
        runCurrent()
        for (id in 1L..15L) db.bookmarkMirrorDao().deleteTarget(shelf.ownerUid, shelf.contentType.code, id)
        insert(shelf, 31..45)
        updateCount(library, 30)
        assertEquals(-45L, changed.await()?.oldestBookmarkSeq)
        val cursor = library.growingTailCursor()
        assertNotNull("总数未变不代表没有新的尾页", cursor)
        assertEquals((31L..45L).toList(), source.load(cursor).items.map { it.feedKey })
    }

    @Test
    fun `regular next page survives deleting its anchor and inserting a new head`() = runTest(dispatcher) {
        for (type in MirrorContentType.entries) {
            val shelf = shelf(type)
            val library = model(shelf)
            insert(shelf, 1..120)
            updateCount(library, 120)
            val source = BookmarkLibraryFeedSource(library, type)
            val first = source.load(null)
            db.bookmarkMirrorDao().deleteTarget(shelf.ownerUid, type.code, 1L)
            db.bookmarkMirrorDao().deleteTarget(shelf.ownerUid, type.code, 60L)
            insert(shelf, 0..0)
            assertEquals((61L..120L).toList(), source.load(first.nextCursor).items.map { it.feedKey })
        }
    }

    @Test
    fun `head and tail growth below the top preserves the current generation`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        library.setMirrorState(state(shelf, complete = true))
        insert(shelf, 1..120)
        updateCount(library, 120)
        val feed = FeedViewModel(BookmarkLibraryFeedSource(library, shelf.contentType), autoLoad = false)
        models.put("feed", feed)
        feed.refresh()
        val first = feed.uiState.first { it.hasLoadedOnce && it.refresh is LoadState.Idle }
        val ui = ui(library, feed, atTop = false)
        insert(shelf, 0..0)
        insert(shelf, 121..135)
        updateCount(library, 136)
        ui.onListCommitted(first)
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "refreshIfStale")
        assertEquals(first, feed.uiState.value)
        feed.loadMore()
        feed.uiState.first { it.items.size == 120 && it.append is LoadState.Idle }
        feed.loadMore()
        val final = feed.uiState.first { it.items.size == 135 && it.append is LoadState.Idle }
        assertEquals((1L..135L).toList(), final.items.map { it.feedKey })
        assertEquals(first.refreshGeneration, final.refreshGeneration)
        ui.destroy()
    }

    @Test
    fun `deleted head with stale statistics does not cause repeated refreshes`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        library.setMirrorState(state(shelf, complete = true))
        insert(shelf, 1..120)
        updateCount(library, 120)
        val feed = FeedViewModel(BookmarkLibraryFeedSource(library, shelf.contentType), autoLoad = false)
        models.put("feed", feed)
        feed.refresh()
        val first = feed.uiState.first { it.hasLoadedOnce && it.refresh is LoadState.Idle }
        val ui = ui(library, feed)
        insert(shelf, 0..0)
        updateCount(library, 121)
        db.bookmarkMirrorDao().deleteTarget(shelf.ownerUid, shelf.contentType.code, 0L)
        ui.onListCommitted(first)
        runCurrent()
        val final = feed.uiState.first { it.refresh is LoadState.Idle }
        assertEquals(first.refreshGeneration + 1, final.refreshGeneration)
        ui.onListCommitted(final)
        runCurrent()
        assertEquals(final, feed.uiState.value)
        ui.destroy()
    }

    @Test
    fun `filtered reverse ordering keeps its existing offset pagination`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        library.setMirrorState(state(shelf, complete = true))
        library.updateFilter { it.copy(keyword = "work", sort = BookmarkSort.BOOKMARK_OLDEST) }
        insert(shelf, 1..90)
        updateCount(library, 90)
        val source = BookmarkLibraryFeedSource(library, shelf.contentType)
        val first = source.load(null)
        assertEquals("60", first.nextCursor)
        assertEquals((90L downTo 31L).toList(), first.items.map { it.feedKey })
        assertEquals((30L downTo 1L).toList(), source.load(first.nextCursor).items.map { it.feedKey })
        assertNull(library.growingTailCursor())
    }

    @Test
    fun `initial query failure recovers when backfill arrives`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        updateCount(library, 0)
        val source = BookmarkLibraryFeedSource(library, shelf.contentType)
        var fail = true
        val onceFailing = object : FeedSource<String> by source {
            override suspend fun load(cursor: String?) = if (fail) {
                fail = false
                error("temporary read failure")
            } else source.load(cursor)
        }
        val feed = FeedViewModel(onceFailing, autoLoad = false)
        models.put("feed", feed)
        val ui = ui(library, feed)
        feed.refresh()
        feed.uiState.first { it.refresh is LoadState.Error }
        insert(shelf, 1..30)
        updateCount(library, 30)
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "refreshIfStale")
        val final = feed.uiState.first { it.hasLoadedOnce && it.refresh is LoadState.Idle }
        assertEquals((1L..30L).toList(), final.items.map { it.feedKey })
        ui.destroy()
    }

    @Test
    fun `stale count cannot repeatedly reopen an already queried short page`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        insert(shelf, 1..60)
        updateCount(library, 60)
        // 计数已经到达 UI，随后取消收藏；下一次异步计数尚未回来。
        for (id in 31L..60L) db.bookmarkMirrorDao().deleteTarget(shelf.ownerUid, shelf.contentType.code, id)
        val source = BookmarkLibraryFeedSource(library, shelf.contentType)
        assertEquals(30, source.load(null).items.size)
        assertNull("同一份过期计数不得再次打开已查过的空尾页", library.growingTailCursor())
        insert(shelf, 31..90)
        updateCount(library, 90)
        assertEquals("30@-30", library.growingTailCursor())
    }

    @Test
    fun `first sync completion populates filter tags and authors from the newly filled shelf`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        ReflectionHelpers.getField<Job>(library, "facetJob").join()
        assertTrue(library.tagFacets.value.isEmpty())
        val row = BookmarkMirrorMapper.fromIllust(
            shelf,
            Illust(id = 1L, tags = listOf(Tag(name = "landscape")), user = User(id = 42L, name = "Artist")),
            -1L, 1, 0L,
        )
        db.bookmarkMirrorDao().writePage(listOf(row.row), row.tags)
        updateCount(library, 1)
        library.setMirrorState(state(shelf, complete = true))
        ReflectionHelpers.getField<Job>(library, "facetJob").join()
        assertEquals(1, library.tagFacets.value.size)
        assertEquals(1, library.authorFacets.value.size)
    }

    @Test
    fun `short filtered pages resume at SQL offset including the final sync batch`() = runTest(dispatcher) {
        // 只有作品书架套全局屏蔽；关注书架不藏人，见 following shelf streams user cards…
        for (type in listOf(MirrorContentType.ILLUST, MirrorContentType.NOVEL)) {
            val shelf = shelf(type)
            val library = model(shelf)
            val source = BookmarkLibraryFeedSource(library, type)
            insert(shelf, 1..29, hidden = true)
            insert(shelf, 30..30)
            updateCount(library, 30)
            val first = source.load(null)
            assertEquals(listOf(30L), first.items.map { it.feedKey })
            assertNull(first.nextCursor)
            assertEquals(30, library.consumedRows)
            assertNull(library.growingTailCursor())

            insert(shelf, 31..45)
            library.setMirrorState(state(shelf, complete = true))
            updateCount(library, 45)
            assertEquals("30@-30", library.growingTailCursor())
            val tail = source.load(library.growingTailCursor())
            assertEquals((31L..45L).toList(), tail.items.map { it.feedKey })
            assertNull(tail.nextCursor)
            assertNull(library.growingTailCursor())
        }
    }

    @Test
    fun `empty and fully hidden pages wait for new rows without re-reading hidden rows`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        val source = BookmarkLibraryFeedSource(library, shelf.contentType)
        updateCount(library, 0)
        assertTrue(source.load(null).items.isEmpty())
        assertNull(library.growingTailCursor())
        insert(shelf, 1..30, hidden = true)
        updateCount(library, 30)
        assertEquals("0", library.growingTailCursor())
        assertTrue(source.load("0").items.isEmpty())
        assertNull(library.growingTailCursor())
        insert(shelf, 31..31)
        updateCount(library, 31)
        assertEquals("30@-30", library.growingTailCursor())
        assertEquals(31L, source.load(library.growingTailCursor()).items.single().feedKey)
    }

    @Test
    fun `unknown and rebuilding shelves reject filters and reset stale ordering`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        assertFalse(library.updateFilter { it.copy(keyword = "missing", sort = BookmarkSort.BOOKMARK_OLDEST) })
        library.setMirrorState(state(shelf, complete = true))
        assertTrue(library.updateFilter { it.copy(keyword = "work", sort = BookmarkSort.BOOKMARK_OLDEST) })
        assertTrue(library.setMirrorState(state(shelf)))
        assertEquals(BookmarkFilter(shelf.key), library.filter.value)

        library.setMirrorState(state(shelf, complete = true))
        library.updateFilter { it.copy(sort = BookmarkSort.RANDOM) }
        val privateShelf = shelf.copy(restrict = MirrorRestrict.PRIVATE)
        library.switchShelf(privateShelf)
        library.setMirrorState(null)
        assertEquals(BookmarkFilter(privateShelf.key), library.filter.value)
        assertNull(library.growingTailCursor())
    }

    @Test
    fun `UI reopens exhausted feed on final batch without refreshing the existing generation`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        insert(shelf, 1..30)
        val feed = FeedViewModel(BookmarkLibraryFeedSource(library, shelf.contentType), autoLoad = false)
        models.put("feed", feed)
        feed.refresh()
        val first = feed.uiState.first { it.hasLoadedOnce && it.refresh is LoadState.Idle }
        assertTrue(first.reachedEnd)
        val context = ContextThemeWrapper(app, R.style.AppTheme)
        val binding = FragmentBookmarkLibraryBinding.inflate(LayoutInflater.from(context))
        val ui = BookmarkLibraryUi(
            Fragment(), binding, binding.feedRoot.feedListView, library, feed, shelf.contentType,
            itemCount = { feed.uiState.value.items.size },
        )
        insert(shelf, 31..45)
        library.setMirrorState(state(shelf, complete = true))
        library.onMirrorChanged()
        library.shelfStats.first { it?.total == 45 }
        ui.onListCommitted(first)
        val final = feed.uiState.first { it.items.size == 45 && it.append is LoadState.Idle }
        assertEquals(first.refreshGeneration, final.refreshGeneration)
        assertEquals((1L..45L).toList(), final.items.map { it.feedKey })
        assertTrue(final.reachedEnd)
        ui.destroy()
    }

    @Test
    fun `growing data does not bypass a paused append budget`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        insert(shelf, 1..60)
        insert(shelf, 61..180, hidden = true)
        val source = object : FeedSource<String> by BookmarkLibraryFeedSource(library, shelf.contentType) {
            override fun pagingPolicy() = FeedPagingPolicy(maxAutoPages = 1, minPageIntervalMs = 0)
        }
        val feed = FeedViewModel(source, autoLoad = false)
        models.put("feed", feed)
        feed.refresh()
        feed.uiState.first { it.hasLoadedOnce && it.refresh is LoadState.Idle }
        feed.loadMore()
        val paused = feed.uiState.first { it.appendPaused && it.append is LoadState.Idle }
        library.onMirrorChanged()
        library.shelfStats.first { it?.total == 180 }
        assertEquals("120@-120", library.growingTailCursor())
        val binding = FragmentBookmarkLibraryBinding.inflate(
            LayoutInflater.from(ContextThemeWrapper(app, R.style.AppTheme)),
        )
        val ui = BookmarkLibraryUi(
            Fragment(), binding, binding.feedRoot.feedListView, library, feed, shelf.contentType,
            itemCount = { feed.uiState.value.items.size },
        )
        ui.onListCommitted(paused)
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "refreshIfStale")
        assertEquals(paused, feed.uiState.value)
        assertEquals("120@-120", feed.currentCursor)
        ui.destroy()
    }

    @Test
    fun `entry allows partial offline shelves and first online visit but respects disabled and database failure`() {
        val network = NetworkStateManager(app)
        val connection = network.networkState as MutableLiveData
        val services = Proxy.newProxyInstance(
            ServicesProvider::class.java.classLoader, arrayOf(ServicesProvider::class.java),
        ) { _, method, _ ->
            check(method.name == "getNetworkStateManager")
            network
        } as ServicesProvider
        val context = object : ContextWrapper(app), ServicesProvider by services {
            override fun getApplicationContext(): Context = this
        }
        val mirror = BookmarkMirrorService(context)
        val shelf = shelf()
        try {
            connection.value = NetworkStateManager.NetworkType.NONE
            assertFalse(mirror.canOpenLibrary(shelf))
            connection.value = NetworkStateManager.NetworkType.WIFI
            assertTrue(mirror.canOpenLibrary(shelf))
            db.bookmarkMirrorDao().upsertState(state(shelf))
            connection.value = NetworkStateManager.NetworkType.NONE
            assertTrue(mirror.canOpenLibrary(shelf))
            Shaft.sSettings.isBookmarkMirrorEnabled = false
            assertFalse(mirror.canOpenLibrary(shelf))
            Shaft.sSettings.isBookmarkMirrorEnabled = true
            connection.value = NetworkStateManager.NetworkType.WIFI
            db.openHelper.writableDatabase.execSQL("DROP TABLE bookmark_mirror_state_table")
            assertFalse(mirror.canOpenLibrary(shelf))
        } finally {
            network.unregisterNetworkCallback()
        }
    }
}
