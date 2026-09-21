package ceui.pixiv.ui.dynamic

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import ceui.lisa.activities.Shaft
import ceui.lisa.core.FilterMapper
import ceui.lisa.model.ListIllust
import ceui.lisa.utils.Settings
import ceui.loxia.Novel
import ceui.pixiv.api.API
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.IllustResponse
import ceui.pixiv.api.model.NovelResponse
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.pixiv.ui.common.NovelFeedItem
import com.google.gson.Gson
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercise the actual following sources and VM refresh/paging, without rendering the lists. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FollowingBookmarkFilterTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var controller: ActivityController<FragmentActivity>
    private var oldApi: API? = null
    private var illustPage = IllustResponse()
    private var novelPage = NovelResponse()
    private val nextPages = mutableMapOf<String, Any>()
    private val requestedPages = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        Shaft.sSettings = Settings()
        Shaft.sGson = Gson()
        oldApi = ReflectionHelpers.getField(Client, "_appApi")
        val api = Proxy.newProxyInstance(API::class.java.classLoader, arrayOf(API::class.java)) { _, method, args ->
            when (method.name) {
                "getFollowingIllusts" -> illustPage
                "getFollowingNovels" -> novelPage
                "generalGet" -> {
                    val url = args!![0] as String
                    requestedPages += url
                    Shaft.sGson.toJson(nextPages.getValue(url)).toResponseBody()
                }
                else -> error("Unexpected API call: ${method.name}")
            }
        } as API
        ReflectionHelpers.setField(Client, "_appApi", api)
        controller = Robolectric.buildActivity(FragmentActivity::class.java).create()
    }

    @After
    fun tearDown() {
        controller.destroy()
        ReflectionHelpers.setField(Client, "_appApi", oldApi)
        Dispatchers.resetMain()
    }

    private fun <T : Fragment> attach(fragment: T): T {
        controller.get().supportFragmentManager.beginTransaction()
            .add(fragment, fragment.javaClass.simpleName)
            .setMaxLifecycle(fragment, Lifecycle.State.CREATED)
            .commitNow()
        return fragment
    }

    private fun Fragment.feedModel(): FeedViewModel<String> =
        ReflectionHelpers.callInstanceMethod(this, "getFeedViewModel")

    private fun illust(id: Long, bookmarked: Boolean?, type: String = "illust") =
        Illust(id = id, visible = true, is_bookmarked = bookmarked, type = type)

    private fun novel(id: Long, bookmarked: Boolean?) =
        Novel(id = id, visible = true, is_bookmarked = bookmarked)

    private suspend fun awaitRefresh(vm: FeedViewModel<String>) {
        ReflectionHelpers.getField<Job>(vm, "refreshJob").join()
        assertTrue(vm.uiState.value.refresh.toString(), vm.uiState.value.refresh is LoadState.Idle)
    }

    private suspend fun append(vm: FeedViewModel<String>) {
        vm.loadMore()
        ReflectionHelpers.getField<Job>(vm, "appendJob").join()
        assertFalse(vm.uiState.value.append.toString(), vm.uiState.value.append is LoadState.Error)
    }

    private fun ids(vm: FeedViewModel<String>) = vm.uiState.value.items.map { it.feedKey }

    @Test
    fun `illust and manga bookmarks disappear on refresh and return when switch is disabled`() = runTest(dispatcher) {
        illustPage = IllustResponse(listOf(illust(1, false), illust(2, true, "manga"), illust(3, null)))
        val vm = attach(FollowingIllustFeedFragment()).feedModel()
        awaitRefresh(vm)
        assertEquals(listOf(1L, 2L, 3L), ids(vm))

        Shaft.sSettings.isDeleteStarIllust = true
        illustPage = illustPage.copy(illusts = illustPage.illusts.map {
            if (it.id == 1L) it.copy(is_bookmarked = true) else it
        })
        vm.refresh()
        awaitRefresh(vm)
        assertEquals(listOf(3L), ids(vm))

        Shaft.sSettings.isDeleteStarIllust = false
        vm.refresh()
        awaitRefresh(vm)
        assertEquals(listOf(1L, 2L, 3L), ids(vm))
    }

    @Test
    fun `novel bookmarks disappear on refresh and are also filtered on later pages`() = runTest(dispatcher) {
        novelPage = NovelResponse(listOf(novel(1, false), novel(2, null)), "novel-next")
        nextPages["novel-next"] = NovelResponse(listOf(novel(3, true), novel(4, false)))
        val vm = attach(FollowingNovelFeedFragment()).feedModel()
        awaitRefresh(vm)
        assertEquals(listOf(1L, 2L), ids(vm))

        Shaft.sSettings.isDeleteStarIllust = true
        novelPage = novelPage.copy(novels = listOf(novel(1, true), novel(2, null)))
        vm.refresh()
        awaitRefresh(vm)
        assertEquals(listOf(2L), ids(vm))
        append(vm)
        assertEquals(listOf(2L, 4L), ids(vm))

        Shaft.sSettings.isDeleteStarIllust = false
        vm.refresh()
        awaitRefresh(vm)
        append(vm)
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(vm))
    }

    @Test
    fun `fully bookmarked first pages continue loading for both following feeds`() = runTest(dispatcher) {
        Shaft.sSettings.isDeleteStarIllust = true
        illustPage = IllustResponse(listOf(illust(1, true)), "illust-next")
        nextPages["illust-next"] = IllustResponse(listOf(illust(2, false)), "illust-last")
        nextPages["illust-last"] = IllustResponse(listOf(illust(3, true), illust(4, false, "manga")))
        val illustVm = attach(FollowingIllustFeedFragment()).feedModel()
        awaitRefresh(illustVm)
        assertEquals(listOf(2L), ids(illustVm))
        append(illustVm)
        assertEquals(listOf(2L, 4L), ids(illustVm))

        novelPage = NovelResponse(listOf(novel(1, true)), "novel-next")
        nextPages["novel-next"] = NovelResponse(listOf(novel(2, false)))
        val novelVm = attach(FollowingNovelFeedFragment()).feedModel()
        awaitRefresh(novelVm)
        assertEquals(listOf(2L), ids(novelVm))
        assertEquals(listOf("illust-next", "illust-last", "novel-next"), requestedPages)
    }

    @Test
    fun `detail handoff respects following filter without changing shared or search filters`() = runTest(dispatcher) {
        illustPage = IllustResponse(listOf(illust(1, false)), "illust-next")
        val fragment = attach(FollowingIllustFeedFragment())
        awaitRefresh(fragment.feedModel())
        val cursor = FollowingIllustFeedFragment::class.java.getDeclaredMethod("getDetailContinuationCursor")
            .apply { isAccessible = true }
        val mapBack = FollowingIllustFeedFragment::class.java.getDeclaredMethod("feedItemFromBean", Illust::class.java)
            .apply { isAccessible = true }
        assertEquals("illust-next", cursor.invoke(fragment))

        Shaft.sSettings.isDeleteStarIllust = true
        assertNull(cursor.invoke(fragment))
        assertNull(mapBack.invoke(fragment, illust(2, true)))
        assertNotNull(mapBack.invoke(fragment, illust(3, false)))
        assertNotNull(IllustFeedItem.of(illust(2, true)))
        assertNotNull(NovelFeedItem.of(novel(2, true)))

        // Search still applies its bookmark-count threshold, but this following-only switch
        // must not remove bookmarked results. Avoid unrelated mute-store disk initialization.
        ReflectionHelpers.setField(IllustMuteStore, "loaded", true)
        val search = FilterMapper().enableFilterStarSize().apply { updateStarSizeLimit(10) }
        val page = ListIllust().apply {
            illusts = mutableListOf(
                illust(2, true).copy(total_bookmarks = 20),
                illust(3, false).copy(total_bookmarks = 5),
            )
        }
        assertEquals(listOf(2L), search.apply(page).list.map { it.id })
        Shaft.sSettings.isDeleteStarIllust = false
        assertNotNull(mapBack.invoke(fragment, illust(2, true)))
        assertEquals("illust-next", cursor.invoke(fragment))
    }
}
