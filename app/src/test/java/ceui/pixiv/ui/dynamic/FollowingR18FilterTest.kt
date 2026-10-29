package ceui.pixiv.ui.dynamic

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import ceui.lisa.activities.Shaft
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
import ceui.lisa.utils.Settings
import com.google.gson.Gson
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * 动态页把全局「R-18 过滤」从「mapper 里丢掉」改成「只打隐藏标记」之后的口径：
 * 条目留在 items 里，只是不进 visibleItems，开关翻转时就地回来、**不再发请求**。
 *
 * 与 [FollowingBookmarkFilterTest] 的差别：那边的「过滤已收藏」仍然是丢，所以断言 items 本身；
 * 这边断言的是 items 与 visibleItems 的差集。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FollowingR18FilterTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var controller: ActivityController<FragmentActivity>
    private var oldSettings: Settings? = null
    private var oldGson: Gson? = null
    private var oldMuteLoaded = false
    private var oldApi: API? = null
    private var illustPage = IllustResponse()
    private var novelPage = NovelResponse()
    /** 动态插画首屏 / 翻页请求的次数：用来证明「开关翻转不重新拉数据」。 */
    private var illustLoads = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        oldSettings = Shaft.sSettings
        oldGson = Shaft.sGson
        oldMuteLoaded = ReflectionHelpers.getField(IllustMuteStore, "loaded")
        Shaft.sSettings = Settings()
        Shaft.sGson = Gson()
        oldApi = ReflectionHelpers.getField(Client, "_appApi")
        val api = Proxy.newProxyInstance(API::class.java.classLoader, arrayOf(API::class.java)) { _, method, _ ->
            when (method.name) {
                "getFollowingIllusts" -> {
                    illustLoads++
                    illustPage
                }
                "getFollowingNovels" -> novelPage
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
        ReflectionHelpers.setField(IllustMuteStore, "loaded", oldMuteLoaded)
        Shaft.sSettings = oldSettings
        Shaft.sGson = oldGson
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

    private fun illust(id: Long, r18: Boolean = false) = Illust(
        id = id,
        visible = true,
        x_restrict = if (r18) 1 else 0,
        is_bookmarked = false,
        type = "illust",
    )

    private fun novel(id: Long, r18: Boolean = false) =
        Novel(id = id, visible = true, x_restrict = if (r18) 1 else 0, is_bookmarked = false)

    private suspend fun awaitRefresh(vm: FeedViewModel<String>) {
        ReflectionHelpers.getField<Job>(vm, "refreshJob").join()
        assertTrue(vm.uiState.value.refresh.toString(), vm.uiState.value.refresh is LoadState.Idle)
    }

    private fun ids(vm: FeedViewModel<String>) = vm.uiState.value.items.map { it.feedKey }

    private fun visibleIds(vm: FeedViewModel<String>) = vm.uiState.value.visibleItems.map { it.feedKey }

    @Test
    fun `r18 items stay in the list and only leave what is rendered`() = runTest(dispatcher) {
        Shaft.sSettings.isR18FilterTempEnable = true
        illustPage = IllustResponse(listOf(illust(1), illust(2, r18 = true), illust(3)))
        val vm = attach(FollowingIllustFeedFragment()).feedModel()
        awaitRefresh(vm)

        // 数据里还在：关掉开关时要原地回来，不能靠重新拉一次
        assertEquals(listOf(1L, 2L, 3L), ids(vm))
        assertEquals(listOf(1L, 3L), visibleIds(vm))
    }

    @Test
    fun `turning the filter off restores items without another request`() = runTest(dispatcher) {
        Shaft.sSettings.isR18FilterTempEnable = true
        illustPage = IllustResponse(listOf(illust(1), illust(2, r18 = true)))
        val fragment = attach(FollowingIllustFeedFragment())
        val vm = fragment.feedModel()
        awaitRefresh(vm)
        assertEquals(listOf(1L), visibleIds(vm))
        val loadsBefore = illustLoads

        // 长按侧边栏头像那个开关的真实路径：改设置 → 广播 → 列表就地重算
        Shaft.sSettings.isR18FilterTempEnable = false
        ReflectionHelpers.callInstanceMethod<Any>(fragment, "onR18FilterChanged")

        assertEquals(listOf(1L, 2L), visibleIds(vm))
        assertEquals("不该为了放回来再发一次请求", loadsBefore, illustLoads)
    }

    @Test
    fun `detail handoff marks r18 instead of dropping it`() {
        Shaft.sSettings.isR18FilterTempEnable = true
        val fragment = attach(FollowingIllustFeedFragment())
        val mapBack = FollowingIllustFeedFragment::class.java
            .getDeclaredMethod("feedItemFromBean", Illust::class.java)
            .apply { isAccessible = true }

        val item = mapBack.invoke(fragment, illust(2, r18 = true)) as IllustFeedItem
        assertNotNull("详情续读进来的 R-18 作品要留在列表里（只藏），不能丢", item)
        assertTrue(item.hidden)
    }

    @Test
    fun `novel feed marks r18 the same way`() = runTest(dispatcher) {
        Shaft.sSettings.isR18FilterTempEnable = true
        novelPage = NovelResponse(listOf(novel(1), novel(2, r18 = true)))
        val vm = attach(FollowingNovelFeedFragment()).feedModel()
        awaitRefresh(vm)

        assertEquals(listOf(1L, 2L), ids(vm))
        assertEquals(listOf(1L), visibleIds(vm))
        assertTrue((vm.uiState.value.items[1] as NovelFeedItem).hidden)
    }
}
