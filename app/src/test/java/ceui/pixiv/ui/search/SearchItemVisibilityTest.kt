package ceui.pixiv.ui.search

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import ceui.lisa.viewmodel.SearchModel
import ceui.loxia.Novel
import ceui.pixiv.api.model.Illust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchItemVisibilityTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private fun mockIllust(id: Long, isBookmarked: Boolean, xRestrict: Int, aiType: Int = 1): Illust {
        return Illust(
            id = id,
            title = "Illust $id",
            is_bookmarked = isBookmarked,
            x_restrict = xRestrict,
            illust_ai_type = aiType,
        )
    }

    private fun mockNovel(id: Long, isBookmarked: Boolean, xRestrict: Int, aiType: Int = 1): Novel {
        return Novel(
            id = id,
            title = "Novel $id",
            is_bookmarked = isBookmarked,
            x_restrict = xRestrict,
            novel_ai_type = aiType,
        )
    }

    @Test
    fun `illust visibility - bookmark filter`() {
        val bookmarked = mockIllust(1L, isBookmarked = true, xRestrict = 0)
        val notBookmarked = mockIllust(2L, isBookmarked = false, xRestrict = 0)

        // 开过滤已收藏：收藏项隐藏，非收藏项可见
        assertTrue(shouldHideSearchIllust(bookmarked, bookmarkFilter = true, r18Restriction = 0, globalR18FilterOn = false))
        assertFalse(shouldHideSearchIllust(notBookmarked, bookmarkFilter = true, r18Restriction = 0, globalR18FilterOn = false))

        // 关过滤已收藏：两项均可见
        assertFalse(shouldHideSearchIllust(bookmarked, bookmarkFilter = false, r18Restriction = 0, globalR18FilterOn = false))
        assertFalse(shouldHideSearchIllust(notBookmarked, bookmarkFilter = false, r18Restriction = 0, globalR18FilterOn = false))
    }

    @Test
    fun `illust visibility - r18 restriction`() {
        val safeIllust = mockIllust(1L, isBookmarked = false, xRestrict = 0)
        val r18Illust = mockIllust(2L, isBookmarked = false, xRestrict = 1)

        // 档位 1：仅安全（R-18 隐藏，全年龄显示）
        assertFalse(shouldHideSearchIllust(safeIllust, bookmarkFilter = false, r18Restriction = 1, globalR18FilterOn = false))
        assertTrue(shouldHideSearchIllust(r18Illust, bookmarkFilter = false, r18Restriction = 1, globalR18FilterOn = false))

        // 档位 2：仅 R-18（全年龄隐藏，R-18 显示）
        assertTrue(shouldHideSearchIllust(safeIllust, bookmarkFilter = false, r18Restriction = 2, globalR18FilterOn = false))
        assertFalse(shouldHideSearchIllust(r18Illust, bookmarkFilter = false, r18Restriction = 2, globalR18FilterOn = false))

        // 档位 0：全部（全局 R-18 开关控制）
        // 全局开：R-18 隐藏
        assertFalse(shouldHideSearchIllust(safeIllust, bookmarkFilter = false, r18Restriction = 0, globalR18FilterOn = true))
        assertTrue(shouldHideSearchIllust(r18Illust, bookmarkFilter = false, r18Restriction = 0, globalR18FilterOn = true))
        // 全局关：全部显示
        assertFalse(shouldHideSearchIllust(safeIllust, bookmarkFilter = false, r18Restriction = 0, globalR18FilterOn = false))
        assertFalse(shouldHideSearchIllust(r18Illust, bookmarkFilter = false, r18Restriction = 0, globalR18FilterOn = false))
    }

    @Test
    fun `illust visibility - ai filter`() {
        val humanIllust = mockIllust(1L, isBookmarked = false, xRestrict = 0, aiType = 1)
        val aiIllust = mockIllust(2L, isBookmarked = false, xRestrict = 0, aiType = 2)

        // 仅看 AI：普通作品隐藏，AI 作品可见
        assertTrue(shouldHideSearchIllust(humanIllust, bookmarkFilter = false, r18Restriction = 0, globalR18FilterOn = false, onlyAi = true))
        assertFalse(shouldHideSearchIllust(aiIllust, bookmarkFilter = false, r18Restriction = 0, globalR18FilterOn = false, onlyAi = true))

        // 既不是仅看 AI 也不屏蔽 AI：两项均可见
        assertFalse(shouldHideSearchIllust(humanIllust, bookmarkFilter = false, r18Restriction = 0, globalR18FilterOn = false, onlyAi = false, excludeAi = false))
        assertFalse(shouldHideSearchIllust(aiIllust, bookmarkFilter = false, r18Restriction = 0, globalR18FilterOn = false, onlyAi = false, excludeAi = false))
    }

    @Test
    fun `novel visibility - bookmark and r18 and ai`() {
        val safeHumanNovel = mockNovel(1L, isBookmarked = false, xRestrict = 0, aiType = 1)
        val r18AiNovel = mockNovel(2L, isBookmarked = true, xRestrict = 1, aiType = 2)

        // 仅安全模式下：
        assertFalse(shouldHideSearchNovel(safeHumanNovel, bookmarkFilter = false, r18Restriction = 1, globalR18FilterOn = false))
        assertTrue(shouldHideSearchNovel(r18AiNovel, bookmarkFilter = false, r18Restriction = 1, globalR18FilterOn = false))

        // 仅看 AI：
        assertTrue(shouldHideSearchNovel(safeHumanNovel, bookmarkFilter = false, r18Restriction = 0, globalR18FilterOn = false, onlyAi = true))
        assertFalse(shouldHideSearchNovel(r18AiNovel, bookmarkFilter = false, r18Restriction = 0, globalR18FilterOn = false, onlyAi = true))

        // 仅 R-18 且开启过滤已收藏：r18AiNovel 因已收藏被藏，safeHumanNovel 因不是 R-18 被藏
        assertTrue(shouldHideSearchNovel(safeHumanNovel, bookmarkFilter = true, r18Restriction = 2, globalR18FilterOn = false))
        assertTrue(shouldHideSearchNovel(r18AiNovel, bookmarkFilter = true, r18Restriction = 2, globalR18FilterOn = false))
    }

    @Test
    fun `illust network spec - unchanged when toggling client-only filters`() {
        val model = SearchModel().apply {
            keyword.value = "test"
            sortType.value = "popular_desc"
            sessionBookmarkFilter.value = false
            r18Restriction.value = 0
            onlyAi.value = false
        }

        val baseSpec = buildIllustNetworkSpec(model)

        // 切「过滤已收藏」：网络 URL 入参完全不变
        model.sessionBookmarkFilter.value = true
        val specAfterBookmarkToggle = buildIllustNetworkSpec(model)
        assertEquals(baseSpec, specAfterBookmarkToggle)

        // 切「R-18 限制」：网络 URL 入参完全不变（Pixiv API search 端点无 R-18 参数）
        model.r18Restriction.value = 1
        val specAfterR18Toggle = buildIllustNetworkSpec(model)
        assertEquals(baseSpec, specAfterR18Toggle)

        // 切「仅看 AI」：网络 URL 入参完全不变（Pixiv API search 端点 search_ai_type 保持 0）
        model.onlyAi.value = true
        val specAfterOnlyAiToggle = buildIllustNetworkSpec(model)
        assertEquals(baseSpec, specAfterOnlyAiToggle)

        // 改关键词或排序：网络 URL 发生改变
        model.sortType.value = "date_desc"
        val specAfterSortChange = buildIllustNetworkSpec(model)
        assertNotEquals(baseSpec, specAfterSortChange)
    }

    @Test
    fun `novel network spec - unchanged for client filters, changed for query params`() {
        val model = SearchModel().apply {
            keyword.value = "test"
            sortType.value = "popular_desc"
            groupBySeries.value = false
            sessionBookmarkFilter.value = false
            r18Restriction.value = 0
            onlyAi.value = false
        }

        val baseSpec = buildNovelNetworkSpec(model)

        // 普通模式下切过滤已收藏、R-18 与仅看 AI：Spec 完全相等
        model.sessionBookmarkFilter.value = true
        model.r18Restriction.value = 1
        model.onlyAi.value = true
        val specAfterClientToggles = buildNovelNetworkSpec(model)
        assertEquals(baseSpec, specAfterClientToggles)

        // 系列归纳模式：mode 作为网页参数发给 ajax，因此 R-18 变动会改变网络 Spec
        model.groupBySeries.value = true
        val groupedSpec1 = buildNovelNetworkSpec(model)
        model.r18Restriction.value = 2
        val groupedSpec2 = buildNovelNetworkSpec(model)
        assertNotEquals(groupedSpec1, groupedSpec2)

        // 但归纳模式下过滤已收藏仍然是客户端纯标记，不变网络 Spec
        model.sessionBookmarkFilter.value = false
        val groupedSpec3 = buildNovelNetworkSpec(model)
        assertEquals(groupedSpec2, groupedSpec3)
    }
}
