package ceui.lisa.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「标签辅助识别 AI」的匹配口径（issue #1252 需求 1）。
 *
 * 打的是 [AiTagRules] 显式传集合的那层——读 Shaft.sSettings 的便捷重载在裸 JVM 单测里会触发
 * Application 子类的类初始化，所以规则集合在生产代码里就作为参数分层。
 */
class AiTagRulesTest {

    private val noDisabled = emptySet<String>()
    private val noCustom = emptySet<String>()

    @Test
    fun `builtin tag hits with default rules`() {
        assertTrue(AiTagRules.isAiTag("AI生成", noDisabled, noCustom))
        assertTrue(AiTagRules.isAiTag("stablediffusion", noDisabled, noCustom))
        assertTrue(AiTagRules.isAiTag("aiイラスト", noDisabled, noCustom))
    }

    @Test
    fun `matching ignores case and surrounding blanks`() {
        assertTrue(AiTagRules.isAiTag("AIGenerated", noDisabled, noCustom))
        assertTrue(AiTagRules.isAiTag("  NovelAI  ", noDisabled, noCustom))
        assertTrue(AiTagRules.isAiTag("AI-GENERATED", noDisabled, noCustom))
    }

    @Test
    fun `matching is exact so variants stay untouched`() {
        // 精确等值：作者手写的变体不会被内置规则吞掉，需要用户自己加一条。
        assertFalse(AiTagRules.isAiTag("ai生成作品集", noDisabled, noCustom))
        assertFalse(AiTagRules.isAiTag("my novelai art", noDisabled, noCustom))
    }

    @Test
    fun `disabled builtin stops matching`() {
        val disabled = setOf("AI生成")
        assertFalse(AiTagRules.isAiTag("AI生成", disabled, noCustom))
        // 停用一条不影响别的内置规则
        assertTrue(AiTagRules.isAiTag("ai作画", disabled, noCustom))
    }

    @Test
    fun `custom tag hits and can be disabled`() {
        val custom = setOf("ai生成作品集")
        assertTrue(AiTagRules.isAiTag("ai生成作品集", noDisabled, custom))
        assertTrue(AiTagRules.isAiTag("AI生成作品集", noDisabled, custom))
        assertFalse(AiTagRules.isAiTag("ai生成作品集", setOf("ai生成作品集"), custom))
    }

    @Test
    fun `blank tag never matches`() {
        assertFalse(AiTagRules.isAiTag(null, noDisabled, setOf("")))
        assertFalse(AiTagRules.isAiTag("", noDisabled, noCustom))
        assertFalse(AiTagRules.isAiTag("   ", noDisabled, noCustom))
    }

    @Test
    fun `hasAiTag needs only one hit`() {
        val tags = listOf("オリジナル", "女の子", "AI生成")
        assertTrue(AiTagRules.hasAiTag(tags, noDisabled, noCustom))
        assertFalse(AiTagRules.hasAiTag(listOf("オリジナル", "女の子"), noDisabled, noCustom))
        assertFalse(AiTagRules.hasAiTag(null, noDisabled, noCustom))
        assertFalse(AiTagRules.hasAiTag(emptyList(), noDisabled, noCustom))
    }

    @Test
    fun `builtin list is clean`() {
        val builtins = AiTagRules.builtinTags()
        assertEquals(24, builtins.size)
        assertEquals(
            "内置清单里不能有大小写 / 空白不同的重复项",
            builtins.size,
            builtins.map { AiTagRules.normalize(it) }.toSet().size,
        )
        builtins.forEach { assertTrue(AiTagRules.isBuiltinTag(it)) }
        assertFalse(AiTagRules.isBuiltinTag("オリジナル"))
    }

    @Test
    fun `enabled count drops disabled builtins only`() {
        assertEquals(24, AiTagRules.enabledBuiltinCount(noDisabled))
        assertEquals(22, AiTagRules.enabledBuiltinCount(setOf("AI生成", "novelai")))
        // 停用集合里混进自定义标签（或已下线的历史词）时，内置计数不该被它影响
        assertEquals(24, AiTagRules.enabledBuiltinCount(setOf("some-custom-tag")))
    }

    @Test
    fun `enabled count works for custom tags too`() {
        val custom = listOf("ai生成作品集", "my-tag")
        assertEquals(2, AiTagRules.enabledCount(custom, noDisabled))
        assertEquals(1, AiTagRules.enabledCount(custom, setOf("MY-TAG")))
    }
}