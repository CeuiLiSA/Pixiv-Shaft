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
 * 「其他条件」里 AI 作品 / 过滤已收藏的「单击 = 临时、长按 = 持久化」契约。
 *
 * 这个功能有两条互不报错的通路，任何一条走岔都只会静默表现成「点了没用」或「点了把我的设置改了」：
 *
 *  1. **不写设置**：单击选出来的档位必须只落到 [SearchModel] 的会话态
 *     （[SearchModel.sessionExcludeAi] / [SearchModel.sessionBookmarkFilter]），
 *     `Shaft.sSettings` 一个字段都不许动——否则搜一次图就顺带改掉首页的 AI 屏蔽、设置页的开关；
 *  2. **临时标记**：只有被标成临时（[SearchFilterV3.aiModeTemporary] /
 *     [SearchFilterV3.bookmarkFilterTemporary]）的档位才写会话态；没标就写 null（跟随全局），
 *     这样设置页改了全局开关，搜索页仍能跟上。
 *
 * 长按那半（写 `isDeleteAIIllust` / `isSearchFilterBookmarked`）在 [OtherFilterSheet] 的
 * 长按监听里，属 UI 交互，这里只钉住桥接层的翻译契约。
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
                aiModeTemporary = true,
                bookmarkFilter = true,
                bookmarkFilterTemporary = true,
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
    fun `未标临时的档位写 null、表示跟随全局`() {
        val searchModel = SearchModel()

        applyToLegacy(
            SearchFilterV3(
                aiMode = AiMode.ExcludeAi,
                aiModeTemporary = false,
                bookmarkFilter = true,
                bookmarkFilterTemporary = false,
            ),
            searchModel,
        )

        assertNull("非临时档位必须回退全局设置，不能锁死成会话值", searchModel.sessionExcludeAi.value)
        assertNull(searchModel.sessionBookmarkFilter.value)
    }

    @Test
    fun `仅看 AI 恒为临时维度`() {
        val searchModel = SearchModel()

        applyToLegacy(
            SearchFilterV3(aiMode = AiMode.OnlyAi, aiModeTemporary = true),
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
    fun `重建 filter 时会话临时态被还原`() {
        val searchModel = SearchModel().apply {
            sessionExcludeAi.value = true
            sessionBookmarkFilter.value = true
        }

        for (isNovel in listOf(false, true)) {
            val seeded = seedFromLegacy(searchModel, isNovel)
            assertEquals("isNovel=$isNovel 时 AI 档位没还原", AiMode.ExcludeAi, seeded.aiMode)
            assertTrue("isNovel=$isNovel 时丢了 AI 临时标记", seeded.aiModeTemporary)
            assertTrue("isNovel=$isNovel 时「过滤已收藏」没还原", seeded.bookmarkFilter)
            assertTrue("isNovel=$isNovel 时丢了收藏过滤临时标记", seeded.bookmarkFilterTemporary)
        }
    }

    @Test
    fun `全局默认种下的档位都标成非临时`() {
        Shaft.sSettings.isDeleteAIIllust = true
        Shaft.sSettings.isSearchFilterBookmarked = true

        for (isNovel in listOf(false, true)) {
            val baseline = SearchFilterV3.fromGlobalDefaults(forNovel = isNovel)
            assertFalse("全局默认不该是临时值", baseline.aiModeTemporary)
            assertFalse(baseline.bookmarkFilterTemporary)
        }
    }
}
