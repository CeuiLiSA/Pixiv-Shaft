package ceui.pixiv.ui.search

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import ceui.lisa.viewmodel.SearchModel
import ceui.pixiv.ui.search.v3.R18Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * issue #1016：`SearchModel`（app-api 那套参数名）→ 网页 ajax 参数名的映射回归。
 *
 * 这张表是整条归纳链路里最容易悄悄错的地方——两套参数名毫无共同点，错一个不会报错，
 * 只会静默返回「筛选没生效」的结果。尤其三条反直觉的：
 *   - 小说侧 `s_tc` 是**正文**（插画侧同名值是标题简介）；
 *   - R-18 三档在网页是 `mode`，不是关键字 hack；
 *   - 「仅看 AI」时**不能**发 `ai_type=1`，否则服务端剔 AI + 客户端只留 AI = 空列表。
 */
class WebNovelSearchParamsTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private fun model(block: SearchModel.() -> Unit = {}) = SearchModel().apply {
        keyword.value = "原神"
        block()
    }

    @Test
    fun `sort maps to web order values`() {
        fun orderOf(sort: String?) =
            buildWebNovelSearchParams(model { sortType.value = sort }, excludeAi = false).order

        assertEquals("date_d", orderOf(SortType.DATE_DESC))
        assertEquals("date", orderOf(SortType.DATE_ASC))
        assertEquals("popular_male_d", orderOf(SortType.POPULAR_MALE_DESC))
        assertEquals("popular_female_d", orderOf(SortType.POPULAR_FEMALE_DESC))
        // 网页只有一个 popular_d：会员人气 / 热度预览 / 机内自带热度全部归一到它
        assertEquals("popular_d", orderOf(SortType.POPULAR_DESC))
        assertEquals("popular_d", orderOf(SortType.POPULAR_PREVIEW))
        assertEquals("popular_d", orderOf(SortType.TRENDING_BUILTIN))
        assertEquals("popular_d", orderOf(null))
    }

    @Test
    fun `search target maps to novel side s_mode`() {
        fun sModeOf(target: String?) =
            buildWebNovelSearchParams(model { searchType.value = target }, excludeAi = false).sMode

        assertEquals("s_tag_full", sModeOf("exact_match_for_tags"))
        // 小说侧 s_tc = 正文（不是插画的标题简介）
        assertEquals("s_tc", sModeOf("text"))
        // 默认档 / 关键词 / 空：网页的「标签 + 标题简介」合并档
        assertEquals("s_tag", sModeOf("partial_match_for_tags"))
        assertEquals("s_tag", sModeOf("keyword"))
        assertEquals("s_tag", sModeOf(null))
    }

    @Test
    fun `r18 restriction drives both web mode and client filter`() {
        fun paramsOf(restriction: Int?) =
            buildWebNovelSearchParams(model { r18Restriction.value = restriction }, excludeAi = false)

        assertEquals("all", paramsOf(0).mode)
        assertEquals(R18Mode.All, paramsOf(0).r18Mode)
        assertEquals("safe", paramsOf(1).mode)
        assertEquals(R18Mode.SafeOnly, paramsOf(1).r18Mode)
        assertEquals("r18", paramsOf(2).mode)
        assertEquals(R18Mode.R18Only, paramsOf(2).r18Mode)
        assertEquals("all", paramsOf(null).mode)
    }

    @Test
    fun `only-ai must not also send the exclude-ai query`() {
        // 「仅看 AI」+ 全局屏蔽 AI 同时开：服务端若再剔掉 AI，客户端又只留 AI，结果恒空
        val onlyAi = buildWebNovelSearchParams(model { onlyAi.value = true }, excludeAi = true)
        assertNull(onlyAi.aiType)
        assertTrue(onlyAi.onlyAi)

        val excluded = buildWebNovelSearchParams(model(), excludeAi = true)
        assertEquals(1, excluded.aiType)
        assertFalse(excluded.onlyAi)

        assertNull(buildWebNovelSearchParams(model(), excludeAi = false).aiType)
    }

    @Test
    fun `users-suffix is appended unless the official bookmark param is used`() {
        val keywordHack = buildWebNovelSearchParams(
            model { starSize.value = "1000users入り" }, excludeAi = false,
        )
        assertEquals("原神 1000users入り", keywordHack.keyword)
        assertNull(keywordHack.bookmarkMin)

        // 走了官方 blt 就不再拼后缀，避免同一维度被收紧两次
        val official = buildWebNovelSearchParams(
            model {
                starSize.value = "1000users入り"
                bookmarkMin.value = 500
            },
            excludeAi = false,
        )
        assertEquals("原神", official.keyword)
        assertEquals(500, official.bookmarkMin)

        // bookmarkMin = 0 等于没设，参数不发
        assertNull(buildWebNovelSearchParams(model { bookmarkMin.value = 0 }, excludeAi = false).bookmarkMin)
    }

    @Test
    fun `explicit keyword snapshot wins over a later model edit`() {
        val searchModel = model { keyword.value = "draft typed later" }
        val params = buildWebNovelSearchParams(
            searchModel = searchModel,
            excludeAi = false,
            keywordSnapshot = "submitted query",
        )

        assertEquals("submitted query", params.keyword)
    }

    @Test
    fun `duration bucket wins over custom date range`() {
        val custom = buildWebNovelSearchParams(
            model {
                startDate.value = "2020-01-01"
                endDate.value = "2020-12-31"
            },
            excludeAi = false,
        )
        assertEquals("2020-01-01", custom.startDate)
        assertEquals("2020-12-31", custom.endDate)

        // 相对档当场算 today−N，覆盖掉自定义起止（与 SearchNovelRepo.resolveDateRange 同规则）
        val bucketed = buildWebNovelSearchParams(
            model {
                startDate.value = "2020-01-01"
                endDate.value = "2020-12-31"
                durationBucket.value = "LastWeek"
            },
            excludeAi = false,
        )
        val today = java.time.LocalDate.now()
        assertEquals(today.minusWeeks(1).toString(), bucketed.startDate)
        assertEquals(today.toString(), bucketed.endDate)

        // 无效档位名当作没设（fail-safe），回落自定义起止
        val bogus = buildWebNovelSearchParams(
            model {
                startDate.value = "2020-01-01"
                durationBucket.value = "NotABucket"
            },
            excludeAi = false,
        )
        assertEquals("2020-01-01", bogus.startDate)
    }

    @Test
    fun `boolean switches only send the query when turned on`() {
        val off = buildWebNovelSearchParams(model(), excludeAi = false)
        assertNull(off.originalOnly)
        assertNull(off.replaceableOnly)
        assertNull(off.genre)
        assertNull(off.workLang)

        val on = buildWebNovelSearchParams(
            model {
                isOriginalOnly.value = true
                isReplaceableOnly.value = true
                genre.value = 2
                lang.value = "ja"
            },
            excludeAi = false,
        )
        assertEquals(1, on.originalOnly)
        assertEquals(1, on.replaceableOnly)
        assertEquals(2, on.genre)
        assertEquals("ja", on.workLang)
    }

    @Test
    fun `body length units map to their own query pairs`() {
        val params = buildWebNovelSearchParams(
            model {
                textLengthMin.value = 5000
                textLengthMax.value = 19999
            },
            excludeAi = false,
        )
        assertEquals(5000, params.textLengthMin)
        assertEquals(19999, params.textLengthMax)
        // 三个单位互斥：只设了字数，另两组不该被填上
        assertNull(params.wordCountMin)
        assertNull(params.readingTimeMin)
    }
}
