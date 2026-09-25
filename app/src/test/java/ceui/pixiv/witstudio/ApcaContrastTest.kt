package ceui.pixiv.witstudio

import ceui.pixiv.witstudio.theme.ApcaContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APCA 实现的正确性自检。
 *
 * 参考值是 APCA-W3 0.1.98G-4g 的公开样例：黑字压纯白 ≈ +106，白字压纯黑 ≈ -107.9。
 * 这两个数不对就说明指数或软钳位抄错了，后面所有基于 |Lc| 的判断都会跟着错。
 */
class ApcaContrastTest {

    @Test
    fun `黑字压纯白命中参考值`() {
        assertEquals(106.0, ApcaContrast.lc(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.5)
    }

    @Test
    fun `白字压纯黑命中参考值且为负`() {
        assertEquals(-107.9, ApcaContrast.lc(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.5)
    }

    @Test
    fun `同色返回零`() {
        assertEquals(0.0, ApcaContrast.lc(0xFF808080.toInt(), 0xFF808080.toInt()), 0.0)
    }

    @Test
    fun `符号区分极性`() {
        // 浅字压暗底（深色模式）为负，深字压浅底（浅色模式）为正。
        // 标签胶囊在深色模式下走的就是负值分支，把符号搞反会算出一个虚高的绝对值。
        assertTrue(ApcaContrast.lc(0xFFE0E0E0.toInt(), 0xFF202020.toInt()) < 0)
        assertTrue(ApcaContrast.lc(0xFF202020.toInt(), 0xFFE0E0E0.toInt()) > 0)
    }

    @Test
    fun `纯白压深底高于正文线`() {
        // 可行性前提：右端极值 75 必须够得到，否则滑条拉到头也到不了这个刻度。
        assertTrue(ApcaContrast.lc(0xFFFFFFFF.toInt(), 0xFF2E2E4B.toInt()) <= -ApcaContrast.BODY_TEXT_MIN)
    }
}