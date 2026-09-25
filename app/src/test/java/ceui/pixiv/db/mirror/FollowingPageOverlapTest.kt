package ceui.pixiv.db.mirror

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 关注列表是 offset 翻页：翻页途中取关一位，后面整体前移，不重叠就会漏掉（重扫时还会误删）
 * 一位仍在关注的人。这里钉住 [overlapNextUrl] 的回退规则。
 */
class FollowingPageOverlapTest {

    private val base = "https://app-api.pixiv.net/v1/user/following?filter=for_android&user_id=1&restrict=public"

    private fun offsetOf(url: String?) = url?.toHttpUrl()?.queryParameter("offset")?.toInt()

    @Test
    fun `next page steps back by the overlap so one unfollow cannot skip a user`() {
        val next = overlapNextUrl(null, "$base&offset=30", 5)
        assertEquals(25, offsetOf(next))
        assertEquals("1", next!!.toHttpUrl().queryParameter("user_id"))
        assertEquals("public", next.toHttpUrl().queryParameter("restrict"))

        assertEquals(50, offsetOf(overlapNextUrl("$base&offset=25", "$base&offset=55", 5)))
    }

    @Test
    fun `never rewinds to or before the page just fetched`() {
        assertEquals(28, offsetOf(overlapNextUrl("$base&offset=27", "$base&offset=30", 5)))
        // 服务端没往前走：原样交给引擎，由它的「游标没变」守卫判到底
        assertEquals(30, offsetOf(overlapNextUrl("$base&offset=30", "$base&offset=30", 5)))
    }

    @Test
    fun `end of list and non offset urls pass through`() {
        assertNull(overlapNextUrl("$base&offset=30", null, 5))
        val keyset = "https://app-api.pixiv.net/v1/user/bookmarks/illust?user_id=1&max_bookmark_id=99"
        assertEquals(keyset, overlapNextUrl(null, keyset, 5))
    }
}
