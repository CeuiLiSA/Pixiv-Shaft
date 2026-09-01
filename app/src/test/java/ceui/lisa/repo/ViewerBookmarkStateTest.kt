package ceui.lisa.repo

import ceui.lisa.model.ListIllust
import ceui.lisa.model.ListNovel
import ceui.lisa.repo.ViewerBookmarkState.withViewerBookmarkState
import ceui.loxia.Novel
import ceui.pixiv.api.model.Illust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** #1063：这条链路返回的 is_bookmarked 不代表当前用户，出仓前必须换成当前用户已知的收藏态。 */
class ViewerBookmarkStateTest {

    @Test
    fun `illust page drops upstream bookmark state and keeps what the viewer already knows`() {
        val page = ListIllust().apply {
            setNext_url("https://app-api.pixiv.net/v1/search/illust?offset=30")
            illusts = listOf(
                Illust(id = 1L, is_bookmarked = true),   // 上游说收藏了，当前用户其实没收藏
                Illust(id = 2L, is_bookmarked = false),  // 上游说没收藏，当前用户其实收藏了（报告人的场景）
                Illust(id = 3L, is_bookmarked = false),  // 当前用户没看过：不知道
            )
        }
        val known = mapOf(1L to false, 2L to true)

        val out = page.withViewerBookmarkState { known[it] }

        assertEquals(listOf(false, true, null), out.illusts.map { it.is_bookmarked })
        assertEquals(listOf(1L, 2L, 3L), out.illusts.map { it.id })
        assertEquals(page.nextUrl, out.nextUrl)
        // 原页对象不动：它已经同步序列化给缓存回填了
        assertEquals(listOf(true, false, false), page.illusts.map { it.is_bookmarked })
    }

    @Test
    fun `novel page behaves the same`() {
        val page = ListNovel().apply {
            setNext_url("https://app-api.pixiv.net/v1/search/novel?offset=30")
            novels = listOf(
                Novel(id = 10L, is_bookmarked = true),
                Novel(id = 11L, is_bookmarked = false),
            )
        }

        val out = page.withViewerBookmarkState { id -> if (id == 11L) true else null }

        assertEquals(listOf(null, true), out.novels.map { it.is_bookmarked })
        assertEquals(page.nextUrl, out.nextUrl)
    }

    @Test
    fun `page without a list is returned untouched`() {
        val page = ListIllust()
        assertSame(page, page.withViewerBookmarkState { error("must not be consulted") })
        assertNull(page.illusts)
    }
}
