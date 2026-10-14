package ceui.pixiv.ui.search.v3

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import ceui.lisa.viewmodel.SearchModel
import com.blankj.utilcode.util.Utils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * 「其他条件」三层持久化（issue #1240）的桥接契约。
 *
 * 单击 = 临时、长按第 1 下 = 仅这里记住、长按第 2 下 = 全局记住（仅"有全局档"的维度）。
 * 两条通路都不能走岔，否则只会静默表现成「点了没用」或「点了把我的设置改了」：
 *
 *  1. **只有「全局」层不写会话态**：临时 / 仅这里记住两层都还没落进全局设置，必须写
 *     [SearchModel] 会话值（[SearchModel.sessionExcludeAi] / [SearchModel.sessionBookmarkFilter] /
 *     [SearchModel.r18Restriction]），搜索才用得上；全局层写 null，跟随设置页开关。
 *  2. **临时 / 仅这里记住都绝不写全局设置**：不许碰 `Shaft.sSettings.isDeleteAIIllust` /
 *     `isSearchFilterBookmarked`——只有长按到「全局记住」才由 [OtherFilterSheet] 落盘。
 *  3. **层级反推**：seed 时用「页记值 → 全局 / 内置默认基线」把会话值反推回 [FilterPersist]，
 *     口径与单击时一致。
 *
 * 长按那半（写全局设置 / 写页记）在 [OtherFilterSheet] 的监听里，属 UI 交互，这里只钉桥接层。
 *
 * 走 Robolectric 而不是纯 JVM：[Settings] 与 [Shaft] 的静态字段要 Android 环境
 * （同 [SearchFilterBookmarkFilterTest]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SearchFilterTemporaryOverrideTest {

    /** 测试会改 [Shaft.sSettings]（进程级静态），跑完必须还原，别污染同 JVM 的其它用例。 */
    private var original: Settings? = null

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

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

    private fun applyToLegacy(filter: SearchFilterV3, searchModel: SearchModel) {
        ReflectionHelpers.callInstanceMethod<Any?>(
            SearchFilterV3LegacyBridge,
            "applyToLegacy",
            ClassParameter.from(SearchFilterV3::class.java, filter),
            ClassParameter.from(SearchModel::class.java, searchModel),
        )
    }

    private fun seedFromLegacy(searchModel: SearchModel, isNovel: Boolean): SearchFilterV3 =
        ReflectionHelpers.callInstanceMethod(
            SearchFilterV3LegacyBridge,
            "seedFromLegacy",
            ClassParameter.from(SearchModel::class.java, searchModel),
            ClassParameter.from(Boolean::class.javaPrimitiveType, isNovel),
        )

    @Test
    fun `临时档只写会话态、绝不碰全局设置`() {
        val searchModel = SearchModel()

        applyToLegacy(
            SearchFilterV3(
                aiMode = AiMode.ExcludeAi,
                aiModePersist = FilterPersist.Temporary,
                bookmarkFilter = true,
                bookmarkFilterPersist = FilterPersist.Temporary,
            ),
            searchModel,
        )

        assertEquals(true, searchModel.sessionExcludeAi.value)
        assertEquals(true, searchModel.sessionBookmarkFilter.value)
        assertFalse("单击选「屏蔽 AI」不该写全局 isDeleteAIIllust", Shaft.sSettings.isDeleteAIIllust)
        assertFalse(
            "单击选「过滤」不该写全局 isSearchFilterBookmarked",
            Shaft.sSettings.isSearchFilterBookmarked,
        )
        assertFalse("屏蔽 AI 档位下 onlyAi 必须为 false", searchModel.onlyAi.value == true)
    }

    @Test
    fun `仅这里记住也写会话态、但绝不碰全局设置`() {
        val searchModel = SearchModel()

        applyToLegacy(
            SearchFilterV3(
                aiMode = AiMode.ExcludeAi,
                aiModePersist = FilterPersist.RememberHere,
                bookmarkFilter = true,
                bookmarkFilterPersist = FilterPersist.RememberHere,
            ),
            searchModel,
        )

        assertEquals("页记的「屏蔽 AI」要进会话态，搜索才用得上", true, searchModel.sessionExcludeAi.value)
        assertEquals(true, searchModel.sessionBookmarkFilter.value)
        assertFalse("长按第 1 下（页记）不该写全局 isDeleteAIIllust", Shaft.sSettings.isDeleteAIIllust)
        assertFalse(
            "长按第 1 下（页记）不该写全局 isSearchFilterBookmarked",
            Shaft.sSettings.isSearchFilterBookmarked,
        )
    }

    @Test
    fun `层级为「全局」的档位写 null、表示跟随全局`() {
        val searchModel = SearchModel()

        applyToLegacy(
            SearchFilterV3(
                aiMode = AiMode.ExcludeAi,
                aiModePersist = FilterPersist.Global,
                bookmarkFilter = true,
                bookmarkFilterPersist = FilterPersist.Global,
            ),
            searchModel,
        )

        assertNull("全局层必须回退全局设置，不能锁死成会话值", searchModel.sessionExcludeAi.value)
        assertNull(searchModel.sessionBookmarkFilter.value)
    }

    @Test
    fun `仅看 AI 走 onlyAi 会话态`() {
        val searchModel = SearchModel()

        applyToLegacy(
            SearchFilterV3(aiMode = AiMode.OnlyAi, aiModePersist = FilterPersist.RememberHere),
            searchModel,
        )

        assertTrue("「仅看 AI」走 onlyAi 会话态喂 FilterMapper", searchModel.onlyAi.value == true)
        assertEquals(
            "「仅看 AI」不是屏蔽档，会话态里应显式写 false",
            false,
            searchModel.sessionExcludeAi.value,
        )
    }

    @Test
    fun `重建 filter 时把会话值反推回「仅这里记住」`() {
        // 页记值写在设置里 → 会话值等于它，就该反推成「仅这里记住」而不是临时
        Shaft.sSettings.searchRememberedAiMode = AiMode.ExcludeAi.name
        Shaft.sSettings.searchRememberedBookmarkFilter = "true"
        val searchModel = SearchModel().apply {
            sessionExcludeAi.value = true
            sessionBookmarkFilter.value = true
        }

        for (isNovel in listOf(false, true)) {
            val seeded = seedFromLegacy(searchModel, isNovel)
            assertEquals("isNovel=$isNovel 时 AI 档位没还原", AiMode.ExcludeAi, seeded.aiMode)
            assertEquals(
                "会话值 == 页记值 → 应还原成「仅这里记住」",
                FilterPersist.RememberHere,
                seeded.aiModePersist,
            )
            assertTrue("isNovel=$isNovel 时「过滤已收藏」没还原", seeded.bookmarkFilter)
            assertEquals(FilterPersist.RememberHere, seeded.bookmarkFilterPersist)
        }
    }

    @Test
    fun `会话值不等于页记值时反推成临时`() {
        Shaft.sSettings.searchRememberedAiMode = AiMode.All.name
        val searchModel = SearchModel().apply { sessionExcludeAi.value = true }

        val seeded = seedFromLegacy(searchModel, isNovel = false)

        assertEquals(AiMode.ExcludeAi, seeded.aiMode)
        assertEquals(FilterPersist.Temporary, seeded.aiModePersist)
    }

    @Test
    fun `全局默认种下的档位都标成「全局」层`() {
        Shaft.sSettings.isDeleteAIIllust = true
        Shaft.sSettings.isSearchFilterBookmarked = true

        for (isNovel in listOf(false, true)) {
            val baseline = SearchFilterV3.fromGlobalDefaults(forNovel = isNovel)
            assertEquals("全局默认不该是临时 / 页记值", FilterPersist.Global, baseline.aiModePersist)
            assertEquals(FilterPersist.Global, baseline.bookmarkFilterPersist)
        }
    }

    @Test
    fun `搜索页「仅这里记住」优先于全局设置`() {
        Shaft.sSettings.isDeleteAIIllust = true          // 全局：屏蔽 AI
        Shaft.sSettings.searchRememberedAiMode = AiMode.All.name   // 但搜索页记住的是「全部」

        val baseline = SearchFilterV3.fromGlobalDefaults()

        assertEquals("页记优先于全局设置", AiMode.All, baseline.aiMode)
        assertEquals(FilterPersist.RememberHere, baseline.aiModePersist)
    }

    @Test
    fun `R-18 的页记被还原、且最多到「仅这里记住」`() {
        Shaft.sSettings.searchRememberedR18Mode = R18Mode.SafeOnly.name

        val baseline = SearchFilterV3.fromGlobalDefaults()
        assertEquals(R18Mode.SafeOnly, baseline.r18Mode)
        assertEquals(FilterPersist.RememberHere, baseline.r18ModePersist)

        // 会话值（r18Restriction）总被 applyToLegacy 写实，seed 仍按页记反推
        val searchModel = SearchModel().apply { r18Restriction.value = 1 }
        val seeded = seedFromLegacy(searchModel, isNovel = false)
        assertEquals(R18Mode.SafeOnly, seeded.r18Mode)
        assertEquals(FilterPersist.RememberHere, seeded.r18ModePersist)
    }
}
