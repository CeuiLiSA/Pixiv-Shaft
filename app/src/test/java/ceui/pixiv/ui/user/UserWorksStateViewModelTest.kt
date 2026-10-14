package ceui.pixiv.ui.user

import ceui.pixiv.ui.common.ContentFilterReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作者作品页「整页被过滤滤空」空态的原因记账：空页追载跨页取并集，首页（刷新）重算，
 * 有内容的一页清空。
 */
class UserWorksStateViewModelTest {

    @Test
    fun `empty-page chase unions reasons across pages`() {
        val vm = UserWorksStateViewModel()
        vm.reportPageFiltered(30, 0, setOf(ContentFilterReason.R18), firstPage = true)
        vm.reportPageFiltered(30, 0, setOf(ContentFilterReason.AI), firstPage = false)
        assertTrue(vm.allItemsFiltered.value)
        assertEquals(setOf(ContentFilterReason.R18, ContentFilterReason.AI), vm.filteredReasons.value)
    }

    @Test
    fun `refresh after toggling a filter off drops its stale reason`() {
        val vm = UserWorksStateViewModel()
        vm.reportPageFiltered(30, 0, setOf(ContentFilterReason.R18, ContentFilterReason.AI), firstPage = true)
        // 用户关掉 R18 过滤后回来刷新，这一页只剩 AI 命中
        vm.reportPageFiltered(30, 0, setOf(ContentFilterReason.AI), firstPage = true)
        assertEquals(setOf(ContentFilterReason.AI), vm.filteredReasons.value)
    }

    @Test
    fun `server empty page keeps the recorded state`() {
        val vm = UserWorksStateViewModel()
        vm.reportPageFiltered(30, 0, setOf(ContentFilterReason.MUTED_TAG), firstPage = true)
        vm.reportPageFiltered(0, 0, emptySet(), firstPage = false)
        assertTrue(vm.allItemsFiltered.value)
        assertEquals(setOf(ContentFilterReason.MUTED_TAG), vm.filteredReasons.value)
    }

    @Test
    fun `a page with shown items clears reasons`() {
        val vm = UserWorksStateViewModel()
        vm.reportPageFiltered(30, 0, setOf(ContentFilterReason.R18), firstPage = true)
        vm.reportPageFiltered(30, 5, setOf(ContentFilterReason.R18), firstPage = false)
        assertFalse(vm.allItemsFiltered.value)
        assertEquals(emptySet<ContentFilterReason>(), vm.filteredReasons.value)
    }
}
