package ceui.lisa.core

import ceui.lisa.activities.Shaft
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustAIType
import ceui.lisa.utils.Settings
import ceui.pixiv.api.model.Illust
import ceui.pixiv.ui.common.IllustMuteStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * 搜索「其他条件」的临时「屏蔽 AI」在「模糊粒子化」强度下的去向。
 *
 * 搜索链路 keepAiForBlur：AI 条目留给 feeds 卡打码。但卡片打码只认全局 isDeleteAIIllust——
 * 全局没开、只在搜索页临时屏蔽时，留下来的 AI 卡既不剔除也不打码，等于没屏蔽。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SearchTemporaryExcludeAiBlurTest {

    private var oldSettings: Settings? = null
    private var oldMuteLoaded = false

    @Before
    fun setUp() {
        oldSettings = Shaft.sSettings
        oldMuteLoaded = ReflectionHelpers.getField(IllustMuteStore, "loaded")
        // 不触发屏蔽名单的磁盘初始化（同 FollowingBookmarkFilterTest）
        ReflectionHelpers.setField(IllustMuteStore, "loaded", true)
        Shaft.sSettings = Settings().apply { aiBlockStrength = 1 }
    }

    @After
    fun tearDown() {
        ReflectionHelpers.setField(IllustMuteStore, "loaded", oldMuteLoaded)
        Shaft.sSettings = oldSettings
    }

    private fun searchIds(excludeAi: Boolean): List<Long> {
        val page = ListIllust().apply {
            illusts = mutableListOf(
                Illust(id = 1, visible = true, illust_ai_type = IllustAIType.CreatedByAI),
                Illust(id = 2, visible = true),
            )
        }
        val mapper = FilterMapper()
        mapper.setSearchExcludeAi(excludeAi)
        mapper.setKeepAiForBlur(true)
        return mapper.apply(page).list.map { it.id }
    }

    @Test
    fun `temporary exclude without the global switch drops AI because cards cannot blur it`() {
        Shaft.sSettings.isDeleteAIIllust = false
        assertEquals(listOf(2L), searchIds(excludeAi = true))
    }

    @Test
    fun `global exclude still keeps AI for the card to blur`() {
        Shaft.sSettings.isDeleteAIIllust = true
        assertEquals(listOf(1L, 2L), searchIds(excludeAi = true))
    }

    @Test
    fun `temporary show all keeps AI`() {
        Shaft.sSettings.isDeleteAIIllust = true
        assertEquals(listOf(1L, 2L), searchIds(excludeAi = false))
    }
}
