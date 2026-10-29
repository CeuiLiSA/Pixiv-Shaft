package ceui.lisa.helper

import ceui.pixiv.api.model.Illust
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全局「R-18 过滤」的判定口径（#151031908 漏放回归）。
 *
 * 只打纯函数 [IllustNovelFilter.hitsR18Tag] / [IllustNovelFilter.isR18Rated]——`judgeR18Filter` 本体要读
 * `Shaft.sSettings`，在裸 JVM 单测里会触发 Application 类初始化（取舍同 AiBlockExemptAuthorTest）。
 *
 * 关键回归：作者只勾「閲覧制限」不打 R-18 标签时 x_restrict 仍为 1，过滤必须认它；
 * 只认标签会出现「卡片角标写着 R-18，R-18 过滤却放行」。
 */
class R18FilterJudgeTest {

    @Test
    fun `literal R-18 tags hit`() {
        assertTrue(IllustNovelFilter.hitsR18Tag("*#R-18,*#女の子,"))
        assertTrue(IllustNovelFilter.hitsR18Tag("*#女の子,*#R-18G,"))
    }

    @Test
    fun `tag needs the whole name, not a prefix`() {
        // "*#R-18改造," 不是 R-18 标签：普通模式按整名匹配，不能靠前缀误伤
        assertFalse(IllustNovelFilter.hitsR18Tag("*#R-18改造,*#女の子,"))
        assertFalse(IllustNovelFilter.hitsR18Tag("*#女の子,*#下着,*#おしり,"))
        assertFalse(IllustNovelFilter.hitsR18Tag(null))
        assertFalse(IllustNovelFilter.hitsR18Tag(""))
    }

    @Test
    fun `x_restrict alone is enough`() {
        // 151031908 的形状：x_restrict=1、sanity_level=6、tags 里没有 R-18
        assertTrue(IllustNovelFilter.isR18Rated(Illust(id = 151031908L, x_restrict = 1, sanity_level = 6)))
        assertTrue(IllustNovelFilter.isR18Rated(Illust(id = 2L, x_restrict = 2)))
    }

    @Test
    fun `all ages and missing field are not R18`() {
        assertFalse(IllustNovelFilter.isR18Rated(Illust(id = 3L, x_restrict = 0)))
        assertFalse(IllustNovelFilter.isR18Rated(Illust(id = 4L)))
    }

    @Test
    fun `sanity_level alone does not make it R18`() {
        // sanity 6 只是「性的・中程度」判定，没有 x_restrict 就不该被 R-18 过滤误杀（与 Mapper 同口径）
        assertFalse(IllustNovelFilter.isR18Rated(Illust(id = 5L, x_restrict = 0, sanity_level = 6)))
    }
}