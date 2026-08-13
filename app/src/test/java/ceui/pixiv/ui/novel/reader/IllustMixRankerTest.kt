package ceui.pixiv.ui.novel.reader

import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.Tag
import ceui.loxia.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * issue #999 相关性回补：混排取材的消费顺序 = 标签重叠优先、同分关注画师优先、
 * 剩余同分按 seed（novelId）稳定洗牌。
 */
class IllustMixRankerTest {

    private fun illust(id: Long, vararg tags: String, followed: Boolean = false) = Illust(
        id = id,
        tags = tags.map { Tag(name = it) },
        user = User(id = id * 10, is_followed = followed),
    )

    private fun novel(vararg tags: String) = Novel(id = 1L, tags = tags.map { Tag(name = it) })

    @Test fun `tag overlap outranks everything else`() {
        val matchTwo = illust(1L, "百合", "オリジナル")
        val matchOne = illust(2L, "百合", "風景", followed = true)
        val matchNone = illust(3L, "メカ", followed = true)
        val ranked = IllustMixRanker.rank(
            listOf(matchNone, matchOne, matchTwo), novel("百合", "オリジナル"), seed = 7L,
        )
        // 重叠 2 个 > 重叠 1 个 + 关注 > 零重叠
        assertEquals(listOf(1L, 2L, 3L), ranked.map { it.id })
    }

    @Test fun `followed artist breaks the tie at equal overlap`() {
        val stranger = illust(1L, "百合")
        val followed = illust(2L, "百合", followed = true)
        val ranked = IllustMixRanker.rank(listOf(stranger, followed), novel("百合"), seed = 3L)
        assertEquals(2L, ranked.first().id)
    }

    @Test fun `translated name matches case-insensitively`() {
        val viaTranslated = Illust(
            id = 1L,
            tags = listOf(Tag(name = "ゆり", translated_name = "Yuri")),
        )
        val noMatch = illust(2L, "メカ", followed = true)
        val ranked = IllustMixRanker.rank(
            listOf(noMatch, viaTranslated),
            Novel(id = 1L, tags = listOf(Tag(name = "YURI"))),
            seed = 1L,
        )
        assertEquals(1L, ranked.first().id)
    }

    @Test fun `same seed gives stable order for tied candidates`() {
        val illusts = (1L..20L).map { illust(it, "無関係$it") }
        val a = IllustMixRanker.rank(illusts, novel("百合"), seed = 42L)
        val b = IllustMixRanker.rank(illusts, novel("百合"), seed = 42L)
        assertEquals(a.map { it.id }, b.map { it.id })
    }

    @Test fun `null novel degrades to seeded shuffle without crashing`() {
        val illusts = (1L..5L).map { illust(it) }
        val a = IllustMixRanker.rank(illusts, novel = null, seed = 9L)
        val b = IllustMixRanker.rank(illusts, novel = null, seed = 9L)
        assertEquals(a.map { it.id }, b.map { it.id })
        assertEquals(illusts.map { it.id }.toSet(), a.map { it.id }.toSet())
    }

    @Test fun `single candidate returned as-is`() {
        val one = listOf(illust(1L, "百合"))
        assertSame(one, IllustMixRanker.rank(one, novel("百合"), seed = 5L))
    }
}
