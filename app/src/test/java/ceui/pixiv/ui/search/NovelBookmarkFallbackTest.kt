package ceui.pixiv.ui.search

import ceui.lisa.model.ListNovel
import ceui.lisa.utils.PixivOperate
import ceui.loxia.Novel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 小说搜索「喜欢！数」的客户端兜底（对齐插画 FilterMapper → PixivOperate.getListWithStarSize）。
 *
 * 非会员端点（popular-preview 预览 / 非会员自己的 token）会静默无视 bookmark_num_min/max。
 * 插画侧靠客户端二次兜底，小说侧此前只有普通 Mapper，参数被无视后过滤整条消失——表现为
 * 「选了喜欢！数但结果没变」。这里钉死兜底的区间语义：闭区间、min/max 任一为 0 表示该端不限、
 * 拿不到收藏数（null）当 0 处理。
 *
 * 打的是 [PixivOperate.getListWithNovelStarSize] —— 纯函数、不读 Shaft.sSettings，
 * 可在裸 JVM 单测里跑（同 [ceui.lisa.helper.NovelSpamFilterTest]）。
 */
class NovelBookmarkFallbackTest {

    private fun novels(vararg bookmarks: Int?): ListNovel = ListNovel().apply {
        novels = bookmarks.mapIndexed { index, bookmark ->
            Novel(id = (index + 1).toLong(), total_bookmarks = bookmark)
        }
    }

    private fun keptIds(vararg bookmarks: Int?, min: Int = 0, max: Int = 0): List<Long> =
        PixivOperate.getListWithNovelStarSize(novels(*bookmarks), min, max).map { it.id }

    @Test
    fun `只设下限时只留达到下限的,等于下限放行`() {
        assertEquals(listOf(2L, 3L), keptIds(10, 100, 999, min = 100))
    }

    @Test
    fun `只设上限时只留不超过上限的,等于上限放行`() {
        assertEquals(listOf(1L, 2L), keptIds(10, 100, 999, max = 100))
    }

    @Test
    fun `上下限同时开时只留区间内的`() {
        assertEquals(listOf(2L), keptIds(10, 100, 999, min = 100, max = 100))
    }

    @Test
    fun `上限为 0 表示不限,下限仍生效`() {
        assertEquals(listOf(2L, 3L), keptIds(10, 100, 999, min = 50, max = 0))
    }

    @Test
    fun `两个阈值都为 0 时不过滤`() {
        assertEquals(listOf(1L, 2L, 3L), keptIds(10, 100, 999))
    }

    @Test
    fun `拿不到收藏数视为 0`() {
        // null 当 0：设了下限 1 就筛掉
        assertEquals(emptyList<Long>(), keptIds(null, min = 1))
        // 只有上限时 0 <= 上限，放行
        assertEquals(listOf(1L), keptIds(null, max = 999))
    }

    @Test
    fun `空列表与 null 列表不崩`() {
        // novels 未赋值 = null
        assertEquals(
            emptyList<Long>(),
            PixivOperate.getListWithNovelStarSize(ListNovel(), 100, 0).map { it.id },
        )
        // 显式空列表
        assertEquals(
            emptyList<Long>(),
            PixivOperate.getListWithNovelStarSize(novels(), 100, 0).map { it.id },
        )
    }
}