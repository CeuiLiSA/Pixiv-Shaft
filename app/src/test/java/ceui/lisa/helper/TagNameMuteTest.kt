package ceui.lisa.helper

import ceui.lisa.models.TagsBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 热门标签 / 搜索页推荐标签按屏蔽规则过滤（pixez#1182）。
 *
 * 打的是 [IllustNovelFilter.isTagNameMuted]：规则由调用方传入，不经过读库的 getMutedTags()。
 */
class TagNameMuteTest {

    private fun rule(name: String, regex: Boolean = false, effective: Boolean = true) =
        TagsBean().apply {
            this.name = name
            filter_mode = if (regex) 1 else 0
            isEffective = effective
        }

    @Test
    fun `exact rule hides the same tag`() {
        assertTrue(IllustNovelFilter.isTagNameMuted("原神", listOf(rule("原神"))))
    }

    @Test
    fun `exact rule does not hide tags that merely contain it`() {
        val rules = listOf(rule("原神"))
        assertFalse(IllustNovelFilter.isTagNameMuted("原神5000users入り", rules))
        assertFalse(IllustNovelFilter.isTagNameMuted("新原神", rules))
    }

    @Test
    fun `regex rule matches like it does on artwork tag strings`() {
        val rules = listOf(rule("users入り", regex = true))
        assertTrue(IllustNovelFilter.isTagNameMuted("原神5000users入り", rules))
        assertFalse(IllustNovelFilter.isTagNameMuted("原神", rules))
    }

    @Test
    fun `disabled rule is ignored`() {
        assertFalse(IllustNovelFilter.isTagNameMuted("原神", listOf(rule("原神", effective = false))))
    }

    @Test
    fun `blank tag name is never muted`() {
        val rules = listOf(rule(".*", regex = true))
        assertFalse(IllustNovelFilter.isTagNameMuted(null, rules))
        assertFalse(IllustNovelFilter.isTagNameMuted("", rules))
    }
}
