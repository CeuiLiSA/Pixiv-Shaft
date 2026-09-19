package ceui.pixiv.ui.library

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentBookmarkLibraryBinding
import ceui.lisa.utils.Settings
import ceui.loxia.Novel
import ceui.loxia.Tag
import ceui.pixiv.api.model.Illust
import ceui.pixiv.db.mirror.*
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.FeedPagingPolicy
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.services.ServicesProvider
import ceui.pixiv.utils.NetworkStateManager
import com.google.gson.Gson
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
            }.row
        }
        db.bookmarkMirrorDao().insertRows(rows)
    }

    @Test
    fun `short filtered pages resume at SQL offset including the final sync batch`() = runTest(dispatcher) {
        for (type in MirrorContentType.entries) {
            val shelf = shelf(type)
            val library = model(shelf)
            val source = BookmarkLibraryFeedSource(library, type)
            insert(shelf, 1..29, hidden = true)
            insert(shelf, 30..30)
            val first = source.load(null)
            assertEquals(listOf(30L), first.items.map { it.feedKey })
            assertNull(first.nextCursor)
            assertEquals(30, library.consumedRows)
            assertNull(library.growingTailCursor(30))

            insert(shelf, 31..45)
            library.setMirrorState(state(shelf, complete = true))
            assertEquals("30", library.growingTailCursor(45))
            val tail = source.load(library.growingTailCursor(45))
            assertEquals((31L..45L).toList(), tail.items.map { it.feedKey })
            assertNull(tail.nextCursor)
            assertNull(library.growingTailCursor(45))
        }
    }

    @Test
    fun `empty and fully hidden pages wait for new rows without re-reading hidden rows`() = runTest(dispatcher) {
        val shelf = shelf()
        val library = model(shelf)
        val source = BookmarkLibraryFeedSource(library, shelf.contentType)
        assertTrue(source.load(null).items.isEmpty())
        assertNull(library.growingTailCursor(0))
        insert(shelf, 1..30, hidden = true)
        assertEquals("0", library.growingTailCursor(30))
        assertTrue(source.load("0").items.isEmpty())
        assertNull(library.growingTailCursor(30))
        insert(shelf, 31..31)
        assertEquals("30", library.growingTailCursor(31))
        assertEquals(31L, source.load("30").items.single().feedKey)
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
        assertNull(library.growingTailCursor(30))
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
        library.totalCount.first { it == 45 }
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
        library.totalCount.first { it == 180 }
        assertEquals("120", library.growingTailCursor(180))
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
        assertEquals("120", feed.currentCursor)
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
