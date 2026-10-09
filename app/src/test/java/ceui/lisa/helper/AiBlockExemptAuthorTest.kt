package ceui.lisa.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏蔽 AI 的「豁免作者」判定：在名单里 **且** 没有被临时停用，才算真的豁免。
 *
 * 打的是 [IllustNovelFilter.isAiExemptAuthor] 显式传集合的那层——读 Shaft.sSettings 的便捷重载
 * 会触发 Application 子类的类初始化，在裸 JVM 单测里必炸，所以集合在生产代码里就作为参数分层。
 */
class AiBlockExemptAuthorTest {

    private val enabled = setOf(100L, 200L)

    @Test
    fun `enabled exempt author is exempt`() {
        assertTrue(IllustNovelFilter.isAiExemptAuthor(100L, enabled, emptySet<Long>()))
    }

    @Test
    fun `temporarily disabled exempt author is not exempt`() {
        assertFalse(IllustNovelFilter.isAiExemptAuthor(100L, enabled, setOf(100L)))
    }

    @Test
    fun `disabling one author does not affect the others`() {
        assertTrue(IllustNovelFilter.isAiExemptAuthor(200L, enabled, setOf(100L)))
    }

    @Test
    fun `author outside the list is not exempt`() {
        assertFalse(IllustNovelFilter.isAiExemptAuthor(999L, enabled, emptySet<Long>()))
    }

    @Test
    fun `non positive id is never exempt`() {
        assertFalse(IllustNovelFilter.isAiExemptAuthor(0L, setOf(0L), emptySet<Long>()))
        assertFalse(IllustNovelFilter.isAiExemptAuthor(-1L, setOf(-1L), emptySet<Long>()))
    }
}