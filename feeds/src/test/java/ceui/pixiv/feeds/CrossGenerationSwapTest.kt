package ceui.pixiv.feeds

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [needsCleanSwapAcrossGenerations] 的判据锁定：**只有共有项被重排**才值得绕开 DiffUtil。
 *
 * 这条判据是「整代替换要不要清空重填」的唯一开关，两个方向都会伤到用户，所以两边都得钉住：
 * - 判宽了（无脑清空重填）：某人最新作品 / 某天日榜这类刷一百次也不变的列表，每次刷新白闪一下；
 * - 判窄了（该绕不绕）：榜单名次一变，跨代 move 就在瀑布流上撕出位置错乱和黑色空档。
 */
class CrossGenerationSwapTest {

    private data class Row(val id: Int) : FeedItem {
        override val feedKey: Any get() = id
    }

    private data class Header(val id: Int) : FeedItem {
        override val feedKey: Any get() = id
    }

    private fun rows(vararg ids: Int): List<FeedItem> = ids.map { Row(it) }

    @Test
    fun `identical generations reuse the diff path`() {
        // 某人的最新作品 / 某天的日榜：刷新拉回一模一样的数据，最常见的情形
        assertFalse(needsCleanSwapAcrossGenerations(rows(1, 2, 3), rows(1, 2, 3)))
    }

    @Test
    fun `refreshing a deep-scrolled list back to its first page reuses the diff path`() {
        // 翻了 3 页共 90 条 → 下拉刷新只拉回第 1 页 30 条。共有项就是那 30 条、两边顺序一致。
        // DiffUtil 只需移除折叠线以下的 60 条 + 顶部原地重绑，绝不该为此清空重填。
        val old = rows(*(1..90).toList().toIntArray())
        val new = rows(*(1..30).toList().toIntArray())
        assertFalse("只拉回第一页不是重排", needsCleanSwapAcrossGenerations(old, new))
    }

    @Test
    fun `newly published work prepended reuses the diff path`() {
        // 作者刚发了新作：新条目插到最前，共有项相对顺序没变 → DiffUtil 干净地插一条
        assertFalse(needsCleanSwapAcrossGenerations(rows(1, 2, 3), rows(99, 1, 2, 3)))
    }

    @Test
    fun `deleted work reuses the diff path`() {
        assertFalse(needsCleanSwapAcrossGenerations(rows(1, 2, 3), rows(1, 3)))
    }

    @Test
    fun `fully disjoint generations need a clean swap`() {
        // 切筛选范围（动态页 全部/公开/私人）、换 tab：两代完全不重合。
        // 没有 move 锚点不代表可以交给 DiffUtil —— 它的全删 + 全插是**后台 diff**，落地时
        // itemAnimator 已恢复，旧内容淡出与新内容淡入同屏 = 用户报的「旧数据往上顶一下再消失」。
        assertTrue(needsCleanSwapAcrossGenerations(rows(1, 2, 3), rows(7, 8, 9)))
    }

    @Test
    fun `empty side reuses the diff path`() {
        assertFalse(needsCleanSwapAcrossGenerations(emptyList(), rows(1, 2)))
        assertFalse(needsCleanSwapAcrossGenerations(rows(1, 2), emptyList()))
    }

    @Test
    fun `reordered survivors need a clean swap`() {
        // 榜单名次变了：1 和 2 换了位置 —— 这就是会在 SGLM 上撕出位置错乱的 move
        assertTrue(needsCleanSwapAcrossGenerations(rows(1, 2, 3), rows(2, 1, 4)))
    }

    @Test
    fun `a lone overlapping item reuses the diff path`() {
        // 单个共有项无论落在新一代的哪个位置都不会产生 move:DiffUtil 求的是最长公共子序列,
        // 单元素序列必然整个落在 LCS 里,它周围只会有 remove/insert。要撕至少得两个共有项被打乱。
        assertFalse(needsCleanSwapAcrossGenerations(rows(1, 2, 3, 4, 5), rows(6, 7, 3, 8, 9)))
        assertFalse(needsCleanSwapAcrossGenerations(rows(1, 2, 3, 4, 5), rows(6, 7, 8, 9, 3)))
    }

    @Test
    fun `overlapping items reordered inside a mostly-fresh page need a clean swap`() {
        // 推荐流/榜单跨代有几个 id 恰好重合、又换了先后 —— memory 记录的原始现场:
        // 这几个重合项被当 move 锚点,在 SGLM 上撕成零散卡片 + 黑色空档再重排。
        // 旧序 [1, 3] → 新序 [3, 1]
        assertTrue(needsCleanSwapAcrossGenerations(rows(1, 2, 3, 4, 5), rows(6, 3, 7, 1, 8)))
    }

    @Test
    fun `same key on different item types counts as no overlap`() {
        // 身份是 (类型, feedKey)：不同类型允许同 key,不该被当成共有项
        val old = listOf(Header(1), Row(2))
        val new = listOf(Row(1), Header(2))
        // 身份是 (类型, feedKey):跨类型同 key 不算共有项 → 两代零重合 → 走清空重填
        assertTrue(needsCleanSwapAcrossGenerations(old, new))
    }
}
