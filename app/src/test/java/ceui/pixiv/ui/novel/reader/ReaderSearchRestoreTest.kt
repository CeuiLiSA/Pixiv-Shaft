package ceui.pixiv.ui.novel.reader

import android.app.Application
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentNovelReaderV3Binding
import ceui.lisa.utils.Settings
import ceui.pixiv.api.model.WebNovel
import ceui.pixiv.db.discovery.DiscoveryPool
import ceui.pixiv.db.discovery.ProfileManager
import ceui.pixiv.ui.novel.reader.model.Page
import ceui.pixiv.ui.novel.reader.model.PageGeometry
import ceui.pixiv.ui.novel.reader.model.ReadingDirection
import ceui.pixiv.ui.novel.reader.paginate.TypeStyle
import ceui.pixiv.ui.novel.reader.render.NovelReaderView
import ceui.pixiv.ui.novel.reader.settings.ReaderParagraphSpacingMigrationTest.MemoryMMKV
import ceui.pixiv.ui.novel.reader.settings.ReaderSettings
import com.blankj.utilcode.util.Utils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Real reader callbacks with controlled text/pagination arrival and in-memory storage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class,
    shadows = [MemoryMMKV::class], instrumentedPackages = ["com.tencent.mmkv"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderSearchRestoreTest {
    private lateinit var db: AppDatabase
    private lateinit var host: ActivityController<FragmentActivity>
    private lateinit var model: NovelReaderV3ViewModel
    private lateinit var binding: FragmentNovelReaderV3Binding

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        Utils.init(app)
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        Shaft.sSettings = Settings()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        MemoryMMKV.values.clear()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        model = NovelReaderV3ViewModel(1137L, DiscoveryPool(app, ProfileManager(app)))
        // Hold loading so the Fragment never starts a network request.
        loadState().value = NovelReaderV3ViewModel.LoadState.Loading
        host = Robolectric.buildActivity(FragmentActivity::class.java)
        host.get().setTheme(R.style.AppTheme)
        host.setup()
    }

    @After fun tearDown() {
        host.pause().stop().destroy()
        ReflectionHelpers.callInstanceMethod<Void>(model, "onCleared")
        AppDatabase.destroyInstance()
        db.close()
    }

    @Test fun `saved query waits for pagination then finds and reaches its first hit`() {
        openReader(ReadingDirection.Horizontal)
        openSearch()
        assertEquals("needle", binding.readerSearchOverlay.editSearchQuery.text.toString())
        assertEquals(0, model.searchResult.value!!.total)

        deliverText()
        assertEquals(0, model.searchResult.value!!.total)
        deliverPages()

        assertEquals(2, model.searchResult.value!!.total)
        val reader = binding.readerStage.getChildAt(0) as NovelReaderView
        assertEquals(1, reader.currentPageIndex())

        // Later layout updates must not reset the selected result to the first one.
        binding.readerSearchOverlay.btnSearchNext.performClick()
        deliverPages()
        assertEquals(1, model.searchResult.value!!.currentIndex)
    }

    @Test fun `scroll mode restores results when text arrives without pagination`() {
        openReader(ReadingDirection.Vertical)
        openSearch()
        deliverText()

        assertEquals(2, model.searchResult.value!!.total)
        assertEquals(null, model.pagination.value)
    }

    @Test fun `closing during load keeps query without reviving hidden search`() {
        openReader(ReadingDirection.Horizontal)
        openSearch()
        binding.readerSearchOverlay.btnCloseSearch.performClick()
        deliverText()
        deliverPages()

        assertEquals(0, model.searchResult.value!!.total)
        assertEquals("needle", ReaderSettings.lastSearchQuery)
        openSearch()
        assertEquals(2, model.searchResult.value!!.total)

        binding.readerSearchOverlay.editSearchQuery.setText("")
        binding.readerSearchOverlay.btnCloseSearch.performClick()
        openSearch()
        assertEquals("", ReaderSettings.lastSearchQuery)
        assertEquals(0, model.searchResult.value!!.total)
    }

    @Test fun `loading with search hidden does not automatically search saved input`() {
        openReader(ReadingDirection.Horizontal)
        deliverText()
        deliverPages()
        assertEquals(0, model.searchResult.value!!.total)
        assertEquals(0, (binding.readerStage.getChildAt(0) as NovelReaderView).currentPageIndex())
    }

    private fun openReader(direction: ReadingDirection) {
        ReaderSettings.readingDirection = direction
        ReaderSettings.lastSearchQuery = "needle"
        val fragment = NovelReaderV3Fragment.newInstance(1137L)
        ReflectionHelpers.setField(fragment, "viewModel\$delegate", lazyOf(model))
        host.get().supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment, "reader").commitNow()
        binding = FragmentNovelReaderV3Binding.bind(fragment.requireView())
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun openSearch() {
        binding.readerBottomBar.btnSearch.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun loadState(): MutableLiveData<NovelReaderV3ViewModel.LoadState> =
        ReflectionHelpers.getField(model, "_loadState")

    private fun deliverText() {
        loadState().value = NovelReaderV3ViewModel.LoadState.Loaded(
            null, WebNovel(title = "Search restoration", text = "plain\n\nneedle\n\nneedle"), emptyList(),
        )
    }

    private fun deliverPages() {
        val pagination = ReflectionHelpers.getField<MutableLiveData<NovelReaderV3ViewModel.PaginationState?>>(model, "_pagination")
        pagination.value = NovelReaderV3ViewModel.PaginationState(
            listOf(Page(0, emptyList(), 0, 4), Page(1, emptyList(), 5, 21)), 0,
            TypeStyle.from(host.get(), ReaderSettings.snapshot(), ReaderSettings.effectiveTheme()),
            PageGeometry(320, 480, 16f, 16f, 16f, 16f),
        )
    }

}
