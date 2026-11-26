package ceui.pixiv.ui.library

import android.app.Application
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.descendants
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.databinding.FragmentBookmarkLibraryBinding
import ceui.lisa.utils.Settings
import ceui.loxia.Novel
import ceui.loxia.User
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.UserPreview
import ceui.pixiv.actionqueue.QueueState
import ceui.pixiv.db.mirror.BookmarkMirrorMapper
import ceui.pixiv.db.mirror.BookmarkMirrorStateEntity
import ceui.pixiv.db.mirror.BookmarkShelf
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.db.mirror.MirrorPhase
import ceui.pixiv.db.mirror.MirrorRestrict
import ceui.pixiv.db.mirror.ValidityFilter
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.LoadState
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

/**
 * 覆盖「我的插画收藏」与「我的关注」本地镜像的失效筛选：
 * - 顶部快捷 chip（「已失效作品 / 已失效关注」）的可见性门控、点击切换、状态回显与列表查询；
 * - 筛选面板的双向联动与关注书架新增的「关注状态」组。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LibraryValidityFilterUiTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val app = RuntimeEnvironment.getApplication()
        Shaft.sSettings = Settings()
        Shaft.sGson = Gson()
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        activity = controller.get()
        activity.setTheme(R.style.AppTheme)
    }

    @After
    fun tearDown() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller.pause().stop().destroy()
        AppDatabase.destroyInstance()
        db.close()
        Dispatchers.resetMain()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `illust library quick chip toggles invalid works and stays synced with filter sheet`() = runTest(dispatcher) {
        val shelf = BookmarkShelf(123L, MirrorContentType.ILLUST, MirrorRestrict.PUBLIC)
        db.bookmarkMirrorDao().insertRows(
            listOf(
                BookmarkMirrorMapper.fromIllust(shelf, Illust(id = 1L, title = "ok", visible = true), -1L, 1, 0L).row,
                BookmarkMirrorMapper.fromIllust(shelf, Illust(id = 2L, title = "", visible = false), -2L, 1, 0L).row,
                BookmarkMirrorMapper.fromIllust(shelf, Illust(id = 3L, title = "", visible = false), -3L, 1, 0L).row,
            ),
        )
        val host = attachHost(shelf)
        val vm = host.libraryViewModel
        val feed = FeedViewModel(BookmarkLibraryFeedSource(vm, shelf.contentType), autoLoad = false)
        val (ui, binding) = createUi(host, vm, feed)
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "setUpChips")

        val invalidChip = findChip(binding, activity.getString(R.string.bookmark_chip_invalid_works))
        // 未补齐前与其余筛选 chip 一起隐藏
        assertEquals(View.GONE, invalidChip.visibility)

        vm.setMirrorState(syncState(shelf, complete = true))
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "renderChips")
        assertEquals(View.VISIBLE, invalidChip.visibility)
        assertFalse(invalidChip.isActivated)

        // 点一下「已失效作品」→ 只剩 visible=false 的 2 件，激活态与筛选计数同步更新
        invalidChip.performClick()
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "renderChips")
        assertEquals(ValidityFilter.INVALID_ONLY, vm.filter.value.validity)
        assertTrue(invalidChip.isActivated)
        assertEquals(
            activity.getString(R.string.bookmark_chip_filter_count, 1),
            findChip(binding, activity.getString(R.string.bookmark_chip_filter_count, 1)).text,
        )
        val invalidPage = feed.uiState.first { it.hasLoadedOnce && it.refresh is LoadState.Idle }
        assertEquals(listOf(2L, 3L), invalidPage.items.map { it.feedKey })

        // 打开筛选面板 → 作品状态已选中「已失效」；在面板切回「仍然有效」→ 顶部 chip 取消激活
        val sheet = openSheet(host)
        runCurrent()
        val sheetRoot = sheet.requireView() as ViewGroup
        val sheetInvalid = findTextView(sheetRoot, activity.getString(R.string.bookmark_filter_state_invalid))
        val sheetValid = findTextView(sheetRoot, activity.getString(R.string.bookmark_filter_state_valid))
        assertTrue(sheetInvalid.isSelected)
        sheetValid.performClick()
        runCurrent()
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "renderChips")
        assertEquals(ValidityFilter.VALID_ONLY, vm.filter.value.validity)
        assertFalse(invalidChip.isActivated)
        assertTrue(sheetValid.isSelected)
        assertEquals(1, host.filterChangeCount)

        // 面板关掉后再点顶部 chip → 直接切回「已失效作品」，再点一次取消回到「不限」
        sheet.dismissNow()
        runCurrent()
        invalidChip.performClick()
        assertEquals(ValidityFilter.INVALID_ONLY, vm.filter.value.validity)
        invalidChip.performClick()
        assertEquals(ValidityFilter.ANY, vm.filter.value.validity)
        ui.destroy()
    }

    @Test
    fun `following library quick chip and filter sheet filter empty-preview followings`() = runTest(dispatcher) {
        val shelf = BookmarkShelf(123L, MirrorContentType.USER, MirrorRestrict.PUBLIC)
        db.bookmarkMirrorDao().insertRows(
            listOf(
                // 正常插画画师
                BookmarkMirrorMapper.fromUserPreview(
                    shelf,
                    UserPreview(
                        user = User(id = 10L, name = "IllustArtist"),
                        illusts = listOf(Illust(id = 100L, visible = true)),
                    ),
                    -1L, 1, 0L,
                ).row,
                // 正常纯小说作者
                BookmarkMirrorMapper.fromUserPreview(
                    shelf,
                    UserPreview(
                        user = User(id = 11L, name = "NovelArtist"),
                        novels = listOf(Novel(id = 101L, visible = true)),
                    ),
                    -2L, 1, 0L,
                ).row,
                // 刚在本地点关注的占位行（尚未拉预览）→ 不应被误判为已失效关注
                BookmarkMirrorMapper.fromUserPreview(
                    shelf,
                    UserPreview(user = User(id = 12L, name = "FreshFollow", is_followed = true)),
                    -3L, 1, 0L,
                    previewLoaded = false,
                ).row,
                // 无任何预览作品的已失效关注
                BookmarkMirrorMapper.fromUserPreview(
                    shelf,
                    UserPreview(user = User(id = 13L, name = "EmptyAccount")),
                    -4L, 1, 0L,
                ).row,
                // 只有不可见预览作品的已失效关注
                BookmarkMirrorMapper.fromUserPreview(
                    shelf,
                    UserPreview(
                        user = User(id = 14L, name = "HiddenWorks"),
                        illusts = listOf(Illust(id = 102L, visible = false)),
                    ),
                    -5L, 1, 0L,
                ).row,
            ),
        )
        val host = attachHost(shelf)
        val vm = host.libraryViewModel
        vm.setMirrorState(syncState(shelf, complete = true))
        val feed = FeedViewModel(BookmarkLibraryFeedSource(vm, shelf.contentType), autoLoad = false)
        val (ui, binding) = createUi(host, vm, feed)
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "setUpChips")

        val invalidChip = findChip(binding, activity.getString(R.string.following_chip_invalid))
        assertEquals(View.VISIBLE, invalidChip.visibility)
        invalidChip.performClick()
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "renderChips")
        assertTrue(invalidChip.isActivated)
        assertEquals(ValidityFilter.INVALID_ONLY, vm.filter.value.validity)

        val invalidPage = feed.uiState.first { it.hasLoadedOnce && it.refresh is LoadState.Idle }
        assertEquals(listOf(13L, 14L), invalidPage.items.map { it.feedKey })
        ReflectionHelpers.getField<Job>(vm, "countJob").join()
        assertEquals(2, vm.resultCount.value)

        // 关注库筛选面板必须露出「关注状态」组、说明文案以及三态连通组
        val sheet = openSheet(host)
        runCurrent()
        val sheetRoot = sheet.requireView() as ViewGroup
        findTextView(sheetRoot, activity.getString(R.string.following_filter_section_state))
        findTextView(sheetRoot, activity.getString(R.string.following_filter_state_hint))
        val sheetInvalid = findTextView(sheetRoot, activity.getString(R.string.bookmark_filter_state_invalid))
        val sheetValid = findTextView(sheetRoot, activity.getString(R.string.bookmark_filter_state_valid))
        assertTrue(sheetInvalid.isSelected)

        sheetValid.performClick()
        ReflectionHelpers.getField<Job>(vm, "countJob").join()
        runCurrent()
        assertEquals(ValidityFilter.VALID_ONLY, vm.filter.value.validity)
        assertEquals(3, vm.resultCount.value)
        sheet.dismissNow()
        runCurrent()

        // 回到「已失效关注」时清理横幅露出；进入队列消费态时切为队列剩余进度并隐藏重复提交按钮
        invalidChip.performClick()
        ReflectionHelpers.getField<Job>(vm, "countJob").join()
        runCurrent()
        assertEquals(listOf(13L, 14L), BookmarkLibraryRepo.targetIds(vm.filter.value))
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "renderCleanupBanner")
        assertEquals(View.VISIBLE, binding.cleanupBanner.visibility)
        assertEquals(View.VISIBLE, binding.cleanupAction.visibility)
        assertEquals(activity.getString(R.string.following_cleanup_invalid_action), binding.cleanupAction.text.toString())
        assertEquals(
            activity.getString(R.string.following_cleanup_invalid_ready_title, "2"),
            binding.cleanupTitle.text.toString(),
        )

        ReflectionHelpers.callInstanceMethod<Unit>(
            ui, "onActionQueueState",
            ReflectionHelpers.ClassParameter.from(QueueState::class.java, QueueState.Working(pending = 2)),
        )
        assertEquals(View.VISIBLE, binding.cleanupBanner.visibility)
        assertEquals(View.GONE, binding.cleanupAction.visibility)
        assertEquals(
            activity.getString(R.string.library_cleanup_queue_running, "2"),
            binding.cleanupTitle.text.toString(),
        )
        // 队列运行中时再从顶栏菜单点清理不得弹出第二个确认弹窗
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "focusInvalidForCleanup")
        runCurrent()
        assertEquals(null, ShadowDialog.getLatestDialog()?.takeIf { it.isShowing })

        // 队列归于 Idle 后自动触发重查并释放忙碌态
        val genBeforeIdle = feed.uiState.value.refreshGeneration
        ReflectionHelpers.callInstanceMethod<Unit>(
            ui, "onActionQueueState",
            ReflectionHelpers.ClassParameter.from(QueueState::class.java, QueueState.Idle),
        )
        val refreshed = feed.uiState.first { it.refreshGeneration > genBeforeIdle && it.refresh is LoadState.Idle }
        assertTrue(refreshed.refreshGeneration > genBeforeIdle)

        // 离开「已失效」筛选且非本页发起清理时，横幅隐藏，不打扰正常浏览
        vm.clearConditions()
        ReflectionHelpers.callInstanceMethod<Unit>(ui, "renderCleanupBanner")
        assertEquals(View.GONE, binding.cleanupBanner.visibility)
        ui.destroy()
    }

    private fun attachHost(shelf: BookmarkShelf): TestHostFragment {
        val host = TestHostFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, host).commitNow()
        host.libraryViewModel.bind(shelf)
        return host
    }

    private fun createUi(
        host: Fragment,
        vm: BookmarkLibraryViewModel,
        feed: FeedViewModel<String>,
    ): Pair<BookmarkLibraryUi, FragmentBookmarkLibraryBinding> {
        val themed = ContextThemeWrapper(activity, R.style.AppTheme)
        val binding = FragmentBookmarkLibraryBinding.inflate(LayoutInflater.from(themed))
        val ui = BookmarkLibraryUi(
            fragment = host,
            binding = binding,
            listView = RecyclerView(themed),
            viewModel = vm,
            feedViewModel = feed,
            contentType = vm.shelf.contentType,
            itemCount = { feed.uiState.value.items.size },
        )
        return ui to binding
    }

    private fun openSheet(host: Fragment): BookmarkFilterSheet {
        BookmarkFilterSheet.show(host)
        host.childFragmentManager.executePendingTransactions()
        shadowOf(Looper.getMainLooper()).idle()
        return host.childFragmentManager.findFragmentByTag("BookmarkFilterSheet") as BookmarkFilterSheet
    }

    private fun findChip(binding: FragmentBookmarkLibraryBinding, label: String): TextView =
        binding.chipRow.children.filterIsInstance<TextView>().single { it.text.toString() == label }

    private fun findTextView(root: ViewGroup, label: String): TextView =
        root.descendants.filterIsInstance<TextView>().single { it.text.toString() == label }

    private fun syncState(shelf: BookmarkShelf, complete: Boolean) = BookmarkMirrorStateEntity(
        shelfKey = shelf.key,
        ownerUid = shelf.ownerUid,
        contentType = shelf.contentType.code,
        restrictCode = shelf.restrict.code,
        phase = if (complete) MirrorPhase.SYNCED else MirrorPhase.BACKFILLING,
        nextUrl = null,
        generation = 1,
        nextBackfillSeq = 0L,
        headSeqCursor = 0L,
        headBlockCeiling = 0L,
        pagesThisRun = 0,
        itemsThisRun = 0,
        firstCompletedAt = if (complete) 1L else 0L,
        lastSyncedAt = 0L,
        lastFullSweepAt = 0L,
        lastErrorAt = 0L,
        lastError = null,
        consecutiveFailures = 0,
        cooldownUntil = 0L,
        updatedAt = 0L,
    )

    class TestHostFragment : Fragment(android.R.layout.simple_list_item_1), BookmarkFilterSheet.Host {
        val libraryViewModel: BookmarkLibraryViewModel by viewModels()
        var filterChangeCount = 0

        override fun onBookmarkFilterChanged() {
            filterChangeCount++
        }
    }
}
