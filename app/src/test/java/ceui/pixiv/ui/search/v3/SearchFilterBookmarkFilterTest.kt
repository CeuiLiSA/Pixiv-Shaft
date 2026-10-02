package ceui.pixiv.ui.search.v3

import android.app.Application
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import ceui.lisa.viewmodel.SearchModel
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * 「过滤已收藏」的取值与联动契约。
 *
 * 这个功能有三个入口、两个数据源，任何一处对不上都不会编译报错，只会静默表现成「开关按了没用」：
 *
 *  1. 设置页「过滤已收藏」弹窗 → 写全局 [Settings.isSearchFilterBookmarked]；
 *  2. 搜索筛选「其他条件」卡片 → 读/写同一个全局设置（[OtherFilterSheet]）；
 *  3. 真正的过滤发生在搜索数据源建条目时**现读**全局设置
 *     （[ceui.pixiv.ui.search.SearchIllustFeedSource] / [ceui.pixiv.ui.search.SearchNovelFeedSource]）。
 *
 * 而 [SearchFilterV3.bookmarkFilter] 只是给 sheet 用的 config 镜像（勾选态 + 「其他条件」徽标），
 * 它由 [SearchFilterV3.fromGlobalDefaults] 从全局设置种下、再被
 * [SearchFilterV3LegacyBridge.seedFromLegacy] 整份重建一次。**重建是整份构造而不是 copy**，
 * 漏字段既不报错也不崩，只会掉回数据类默认值——线上就是这么出现「设置页已开、搜索也确实过滤了，
 * 但 sheet 里勾选还是『不过滤』」的（过滤读全局设置、勾选读镜像，两者分了叉）。
 *
 * 走 Robolectric 而不是纯 JVM：[Settings] 与 [Shaft] 的静态字段要 Android 环境
 * （同 [ceui.lisa.utils.ViewerDismissSettingsTest]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SearchFilterBookmarkFilterTest {

    /** 测试会改 [Shaft.sSettings]（进程级静态），跑完必须还原，别污染同 JVM 的其它用例。 */
    private var original: Settings? = null

    @Before
    fun setUp() {
        Utils.init(RuntimeEnvironment.getApplication())
        original = Shaft.sSettings
        Shaft.sSettings = Settings()
    }

    @After
    fun tearDown() {
        Shaft.sSettings = original
    }

    @Test
    fun `默认档是不过滤`() {
        assertFalse("全新 filter 必须默认不过滤，升级用户不该被静默过滤", SearchFilterV3().bookmarkFilter)
    }

    @Test
    fun `activeCount 把过滤已收藏算作一个非默认维度`() {
        val off = SearchFilterV3(bookmarkFilter = false)
        val on = SearchFilterV3(bookmarkFilter = true)

        // 相对断言：默认档位将来变动也不会误伤这条用例，它只钉「开与不开差 1」
        assertEquals(
            off.activeCount(isNovel = false) + 1,
            on.activeCount(isNovel = false),
        )
        assertEquals(
            off.activeCount(isNovel = true) + 1,
            on.activeCount(isNovel = true),
        )
    }

    @Test
    fun `全局默认把设置种进 filter`() {
        Shaft.sSettings.isSearchFilterBookmarked = true

        assertTrue(SearchFilterV3.fromGlobalDefaults().bookmarkFilter)
        assertTrue(SearchFilterV3.fromGlobalDefaults(forNovel = true).bookmarkFilter)
    }

    /**
     * 回归：`seedFromLegacy` 是**整份构造**（不是 `copy`），漏字段不会编译报错，只会静默掉回
     * 数据类默认值。它的 KDoc 写明「SearchModel 全空 → 与 baseline 等价」，这里就把这条契约
     * 钉死：把 bookmarkFilter 打开，任何一处漏传都会让 baseline 与 seed 不相等。
     *
     * （只关着测不出来——默认值恰好也是 false，正是线上漏检的原因。）
     */
    @Test
    fun `空 SearchModel 种出的 filter 与全局基线逐字段相等`() {
        Shaft.sSettings.isSearchFilterBookmarked = true

        for (isNovel in listOf(false, true)) {
            val baseline = SearchFilterV3.fromGlobalDefaults(forNovel = isNovel)
            val seeded = ReflectionHelpers.callInstanceMethod<SearchFilterV3>(
                SearchFilterV3LegacyBridge,
                "seedFromLegacy",
                ClassParameter.from(SearchModel::class.java, SearchModel()),
                ClassParameter.from(Boolean::class.javaPrimitiveType, isNovel),
            )
            assertEquals("isNovel=$isNovel 时 seed 与 baseline 分了叉", baseline, seeded)
        }
    }

    @Test
    fun `设置项默认关闭、可往返、老备份缺 key 读默认`() {
        val fresh = Settings()
        assertFalse(fresh.isSearchFilterBookmarked)

        fresh.isSearchFilterBookmarked = true
        assertTrue(
            "调过的值不能在一次存-读之后悄悄变回默认",
            Gson().fromJson(Gson().toJson(fresh), Settings::class.java).isSearchFilterBookmarked,
        )

        // 老版本导出的备份里没有 searchFilterBookmarked：字段初始化器照跑 → 默认不过滤
        val restored = Gson().fromJson("""{"themeIndex":1}""", Settings::class.java)
        assertFalse(restored.isSearchFilterBookmarked)
    }
}
