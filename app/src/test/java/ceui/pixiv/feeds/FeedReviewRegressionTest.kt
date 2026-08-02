package ceui.pixiv.feeds

import ceui.loxia.Comment
import ceui.loxia.User
import ceui.loxia.UserPreview
import ceui.pixiv.ui.common.toUserFeedItems
import ceui.pixiv.ui.detail.ArtworkCommentsItem
import ceui.pixiv.ui.recommend.HotWorksSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * feeds 框架整体 review 里定位到的几个缺陷的回归测试。每个用例都以「修复前会怎么错」
 * 的方式命名，防止后来的重构把这些语义悄悄改回去。
 */
class FeedReviewRegressionTest {

    // ── 详情页评论区块：本地发评论不得顶替「已从服务端拉过」 ─────────────────────

    private fun comment(id: Long) = Comment(id = id, comment = "c$id")

    /**
     * 原缺陷：`prepend` 把 comments 从 null 变成非空，而渲染器的重试触发条件是
     * `comments == null` —— 于是「拉取失败 → 用户发一条评论」之后，评论区块再也不会重拉，
     * 服务端已有评论在本视图生命周期内全部消失。
     */
    @Test
    fun `本地发评论后仍然认为需要拉取服务端评论`() {
        val fresh = ArtworkCommentsItem(illustId = 1, illustTitle = "t", illustAuthorId = 2)
        assertFalse("刚建出来还没拉过", fresh.fetched)
        assertTrue("没拉过且无内容 = 加载态", fresh.isLoading)

        // 懒加载失败（条目原样不动），用户发了一条本地评论
        val afterLocalPost = fresh.prepend(comment(100))
        assertFalse("发评论不等于拉过评论", afterLocalPost.fetched)
        assertFalse("已有本地内容可展示，不该再画加载态", afterLocalPost.isLoading)
        assertEquals(listOf(100L), afterLocalPost.comments?.map { it.id })

        // 区块因此仍会被重新触发，服务端评论并入且本地那条排在最前
        val afterFetch = afterLocalPost.withComments(listOf(comment(100), comment(99)))
        assertTrue("拉取成功后才置 fetched", afterFetch.fetched)
        assertFalse(afterFetch.isLoading)
        assertEquals("本地已发的排前、按 id 去重", listOf(100L, 99L), afterFetch.comments?.map { it.id })
    }

    /** 服务端确实没有评论时：拉过了就不该再触发，空态而不是永久加载态。 */
    @Test
    fun `拉到空评论也算拉过`() {
        val item = ArtworkCommentsItem(1, "t", 2).withComments(emptyList())
        assertTrue(item.fetched)
        assertFalse("拉过就不是加载态", item.isLoading)
        assertEquals(emptyList<Comment>(), item.comments)
    }

    /** fetched 参与 data class 的内容比较，否则区块状态翻转不会触发重绑。 */
    @Test
    fun `fetched 变化会被 DiffUtil 看见`() {
        val before = ArtworkCommentsItem(1, "t", 2, comments = listOf(comment(1)))
        val after = before.withComments(listOf(comment(1)))
        assertEquals("内容一致", before.comments?.map { it.id }, after.comments?.map { it.id })
        assertNotEquals("但 fetched 翻了，条目必须不相等", before, after)
    }

    // ── 用户列表：user 为 null 的预览不得塌成同一个身份 ───────────────────────────

    /**
     * 原缺陷：`UserFeedItem.feedKey` 是 `user?.id ?: 0L`，多条 user 为 null 的预览
     * 身份全塌成 0L，被框架 dedupByIdentity 静默丢到只剩一条。
     */
    @Test
    fun `映射用户预览时丢掉无 user 的脏数据`() {
        val previews = listOf(
            UserPreview(user = User(id = 11L)),
            UserPreview(user = null),
            UserPreview(user = null),
            UserPreview(user = User(id = 22L)),
        )
        val items = previews.toUserFeedItems()
        assertEquals(listOf(11L, 22L), items.map { it.user?.id })
        // 身份两两不同 → 不会被 dedupByIdentity 吃掉
        assertEquals(2, items.map { it.feedKey }.toSet().size)
    }

    /** withFollowed 已是目标态时必须原样返回同一实例（updateItems 据此判定 no-op）。 */
    @Test
    fun `withFollowed 幂等时返回同一实例`() {
        val item = previewItemOf(id = 7L, followed = true)
        assertSame(item, item.withFollowed(true))
        assertNotEquals(item.user?.is_followed, item.withFollowed(false).user?.is_followed)
    }

    private fun previewItemOf(id: Long, followed: Boolean) =
        UserPreview(user = User(id = id, is_followed = followed)).let { listOf(it).toUserFeedItems() }
            .single()

    // ── 枚举参数解析不得因为脏 Bundle 崩页面 ─────────────────────────────────────

    /** 原缺陷：`HotWorksSource.valueOf(脏字符串)` 抛 IllegalArgumentException 打崩页面。 */
    @Test
    fun `热度榜来源解析认不出就退回默认`() {
        assertEquals(HotWorksSource.TRENDING, HotWorksSource.ofName("TRENDING"))
        assertEquals(HotWorksSource.RECENT, HotWorksSource.ofName("RECENT"))
        assertEquals(HotWorksSource.TRENDING, HotWorksSource.ofName(null))
        assertEquals(HotWorksSource.TRENDING, HotWorksSource.ofName(""))
        assertEquals(HotWorksSource.TRENDING, HotWorksSource.ofName("garbage"))
    }
}
