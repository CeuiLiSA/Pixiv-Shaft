package ceui.pixiv.ui.search.v3

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.Settings
import ceui.lisa.viewmodel.SearchModel
import com.blankj.utilcode.util.Utils
import org.junit.After
import org.junit.Assert.assertEquals
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
 * 「可能妨碍搜索的作品」维度（app-api `include_potential_violation_works`）的契约。
 *
 * 三条不能走岔，否则表现成「开关点了没用」或「默认档把老用户的结果集改了」：
 *
 *  1. **默认档是「显示」**：本 app 历史上从不传这个参数，服务端默认包含；把它改成默认「不显示」
 *     会让部分关键词直接搜不到（#906 那类回归）。所以默认值必须是 true，且不计入非默认维度。
 *  2. **显示档发 null（不传参数），不显示档发 false**：显式 true 与服务端默认等价，但会让请求
 *     URL 变一个样，把 [ceui.pixiv.shaftapi.Nana7miSearchCache] 里的既有条目整片作废。
 *  3. **层级反推与其它「其他条件」维度同口径**：没有全局档，页记命中就是「仅这里记住」，
 *     否则落在「全局」（＝内置默认）或「临时」。
 *
 * 走 Robolectric：Settings 与 Shaft 的静态字段要 Android 环境（同
 * [SearchFilterTemporaryOverrideTest]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SearchFilterPotentialViolationTest {

    /** 用例会改进程级静态 Shaft.sSettings，跑完必须还原。 */
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
    fun `默认档是显示`() {
        assertTrue(
            "默认必须显示（不传参数）—— 关掉会让部分关键词直接搜不到",
            SearchFilterV3().includePotentialViolationWorks,
        )
    }

    @Test
    fun `只有关掉才计入非默认维度`() {
        val shown = SearchFilterV3(includePotentialViolationWorks = true)
        val hidden = SearchFilterV3(includePotentialViolationWorks = false)
        assertEquals(
            shown.activeCount(isNovel = false) + 1,
            hidden.activeCount(isNovel = false),
        )
        assertEquals(
            shown.activeCount(isNovel = true) + 1,
            hidden.activeCount(isNovel = true),
        )
    }

    @Test
    fun `显示档写 null 让请求不带这个参数`() {
        val searchModel = SearchModel()
        applyToLegacy(SearchFilterV3(includePotentialViolationWorks = true), searchModel)
        assertNull(
            "显示档必须写 null（不传参数），显式 true 会让请求 URL 变样、搜索缓存整片失效",
            searchModel.includePotentialViolationWorks.value,
        )
    }

    @Test
    fun `不显示档写 false 让参数真的发出去`() {
        val searchModel = SearchModel()
        applyToLegacy(SearchFilterV3(includePotentialViolationWorks = false), searchModel)
        assertEquals(false, searchModel.includePotentialViolationWorks.value)
    }

    @Test
    fun `seed 把会话值反推回层级`() {
        val shown = seedFromLegacy(SearchModel(), isNovel = false)
        assertEquals(true, shown.includePotentialViolationWorks)
        assertEquals(FilterPersist.Global, shown.potentialViolationPersist)

        val hiddenModel = SearchModel()
        applyToLegacy(SearchFilterV3(includePotentialViolationWorks = false), hiddenModel)
        val hidden = seedFromLegacy(hiddenModel, isNovel = false)
        assertEquals(false, hidden.includePotentialViolationWorks)
        assertEquals(FilterPersist.Temporary, hidden.potentialViolationPersist)
    }

    @Test
    fun `页记的档位在 seed 时标成仅这里记住`() {
        Shaft.sSettings.searchRememberedPotentialViolation = "false"
        val searchModel = SearchModel()
        applyToLegacy(SearchFilterV3(includePotentialViolationWorks = false), searchModel)

        val seeded = seedFromLegacy(searchModel, isNovel = false)
        assertEquals(false, seeded.includePotentialViolationWorks)
        assertEquals(FilterPersist.RememberHere, seeded.potentialViolationPersist)
    }

    @Test
    fun `全局默认读页记`() {
        assertEquals(true, SearchFilterV3.fromGlobalDefaults().includePotentialViolationWorks)
        assertEquals(
            FilterPersist.Global,
            SearchFilterV3.fromGlobalDefaults().potentialViolationPersist,
        )

        Shaft.sSettings.searchRememberedPotentialViolation = "false"
        val remembered = SearchFilterV3.fromGlobalDefaults()
        assertEquals(false, remembered.includePotentialViolationWorks)
        assertEquals(FilterPersist.RememberHere, remembered.potentialViolationPersist)
    }
}