package ceui.pixiv.ui.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [VerbatimRatio] 的纯函数单测:归一化、命中率、阈值与长度下限。 */
class VerbatimRatioTest {

    @Test
    fun `归一化只保留字母数字`() {
        assertEquals("こんにちは世界", VerbatimRatio.normalize("「こんにちは、世界！」"))
        assertEquals("abc123", VerbatimRatio.normalize("a b\tc\n1-2.3"))
        assertEquals("", VerbatimRatio.normalize("！？…、。😀 "))
    }

    @Test
    fun `归一化剔除链接`() {
        // 整条就是链接 → 归一化后为空
        assertEquals("", VerbatimRatio.normalize("https://pixiv.net/artworks/123"))
        // 链接夹在正文里:链接剔除,中文保留
        assertEquals("你好世界", VerbatimRatio.normalize("https://pixiv.net/artworks/123 你好世界"))
        // 链接后紧跟中文标点:不能吞掉后面的中文
        assertEquals("见说明", VerbatimRatio.normalize("见 https://a.com/xyz，说明"))
        assertEquals("官网", VerbatimRatio.normalize("www.pixiv.net 官网"))
    }

    @Test
    fun `译文里原样保留的链接不算原样度`() {
        // 原文归一化只剩「详见感谢」,译文(含被原样保留的链接)里一个字都找不到 → 0
        val original = "详见 https://pixiv.net/artworks/123 感谢"
        val translated = "See https://pixiv.net/artworks/123 thanks"
        assertEquals(0.0, VerbatimRatio.ratio(original, translated), 0.0001)
        assertFalse(VerbatimRatio.isLikelyVerbatim(original, translated))
    }

    @Test
    fun `原样回显命中率为 1`() {
        val original = "こんにちは、世界！"
        val echoed = "「こんにちは、世界！」"
        assertEquals(1.0, VerbatimRatio.ratio(original, echoed), 0.0001)
        assertTrue(VerbatimRatio.isLikelyVerbatim(original, echoed))
        assertEquals(100, VerbatimRatio.percent(original, echoed))
    }

    @Test
    fun `正经翻译不会被判原样`() {
        assertFalse(VerbatimRatio.isLikelyVerbatim("こんにちは、世界！", "你好，世界！"))
        assertFalse(VerbatimRatio.isLikelyVerbatim("おはようございます", "Good morning"))
    }

    @Test
    fun `短于长度下限不判定`() {
        assertEquals(1.0, VerbatimRatio.ratio("世界", "世界"), 0.0001)
        assertFalse(VerbatimRatio.isLikelyVerbatim("世界", "世界"))
    }

    @Test
    fun `纯符号原文不判定`() {
        assertFalse(VerbatimRatio.isLikelyVerbatim("！！？", "！！？"))
        assertEquals(0.0, VerbatimRatio.ratio("！！？", "！！？"), 0.0001)
    }

    @Test
    fun `部分命中按比例`() {
        // 4 字里命中 2 字 → 0.5,低于阈值
        assertEquals(0.5, VerbatimRatio.ratio("あいうえ", "あいXY"), 0.0001)
        assertFalse(VerbatimRatio.isLikelyVerbatim("あいうえ", "あいXY"))
        // 5 字里命中 4 字 → 0.8,达到阈值
        assertTrue(VerbatimRatio.isLikelyVerbatim("あいうえお", "あいうえZ"))
    }

    @Test
    fun `译文为空返回 0 且不判定`() {
        assertEquals(0.0, VerbatimRatio.ratio("こんにちは", ""), 0.0001)
        assertFalse(VerbatimRatio.isLikelyVerbatim("こんにちは", ""))
    }
}