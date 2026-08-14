package ceui.pixiv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自定义主题色（issue #1014）的输入解析。
 *
 * 只打 [CustomThemeColor.normalize] / [CustomThemeColor.toHex] 这两个纯函数 —— 它们是用户
 * 手敲的 HEX 进系统的唯一入口，解错了就是主题色错。其余部分要么碰 `Shaft.sSettings`
 * （触发 Application 类初始化，裸 JVM 单测里必炸），要么碰 API 30 的 ResourcesLoader，
 * 都只能真机验。
 */
class CustomThemeColorTest {

    @Test
    fun `正常六位色值原样通过并统一成大写`() {
        assertEquals("#686BDD", CustomThemeColor.normalize("#686bdd"))
        assertEquals("#686BDD", CustomThemeColor.normalize("#686BDD"))
    }

    @Test
    fun `井号可省略,首尾空白容忍`() {
        assertEquals("#686BDD", CustomThemeColor.normalize("686bdd"))
        assertEquals("#686BDD", CustomThemeColor.normalize("  #686bdd  "))
    }

    @Test
    fun `三位缩写按 CSS 规则逐位翻倍`() {
        assertEquals("#AABBCC", CustomThemeColor.normalize("#abc"))
        assertEquals("#FFFFFF", CustomThemeColor.normalize("fff"))
        assertEquals("#000000", CustomThemeColor.normalize("#000"))
    }

    @Test
    fun `八位带透明度的色值一律拒绝`() {
        // 半透明的 colorPrimary 会把所有拿它当实底的控件染穿，宁可让用户重填
        assertNull(CustomThemeColor.normalize("#80686BDD"))
        assertNull(CustomThemeColor.normalize("80686BDD"))
    }

    @Test
    fun `非法输入返回 null 而不是抛`() {
        assertNull(CustomThemeColor.normalize(null))
        assertNull(CustomThemeColor.normalize(""))
        assertNull(CustomThemeColor.normalize("#"))
        assertNull(CustomThemeColor.normalize("#GGGGGG"))
        assertNull(CustomThemeColor.normalize("#12345"))
        assertNull(CustomThemeColor.normalize("红色"))
    }

    @Test
    fun `toHex 丢掉 alpha 只留 RRGGBB`() {
        assertEquals("#686BDD", CustomThemeColor.toHex(0xFF686BDD.toInt()))
        assertEquals("#000000", CustomThemeColor.toHex(0xFF000000.toInt()))
        assertEquals("#FFFFFF", CustomThemeColor.toHex(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `normalize 和 toHex 互为逆运算`() {
        val hex = CustomThemeColor.normalize("#03d0bf")!!
        val color = 0xFF000000.toInt() or hex.removePrefix("#").toInt(16)
        assertEquals(hex, CustomThemeColor.toHex(color))
    }
}
