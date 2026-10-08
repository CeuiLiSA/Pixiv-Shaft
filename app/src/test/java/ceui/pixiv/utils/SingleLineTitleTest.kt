package ceui.pixiv.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「单行展示面」标题折行规则（[singleLineTitle]）—— issue #1200。
 *
 * 原始数据来自 pixiv：illust 150482199 的标题就是 `神子   \n\n八重神子，Yae Miko，原神`。
 * 官 app 与 V3 详情页 hero 标题都原样排成多行（中间那行是空的），只有经典详情页那个
 * 16dp 固定高的单行盒需要折平，见 [singleLineTitle] 的注释。
 */
class SingleLineTitleTest {

    @Test
    fun `issue 150482199 的真实标题折成一行且后半段不丢`() {
        val raw = "神子   \n\n八重神子，Yae Miko，原神"
        val flat = raw.singleLineTitle()
        assertEquals("神子 八重神子，Yae Miko，原神", flat)
        assertTrue("后半段必须还在：$flat", flat.contains("八重神子"))
        assertFalse("不能残留换行", flat.contains('\n'))
        assertFalse("不能残留连续空白", flat.contains("  "))
    }

    @Test
    fun `各类换行与制表符都折成一个空格`() {
        assertEquals("A B", "A\n\nB".singleLineTitle())
        assertEquals("A B", "A\r\nB".singleLineTitle())
        assertEquals("A B", "A\rB".singleLineTitle())
        assertEquals("A B", "A\t\t B".singleLineTitle())
        assertEquals("A B", "A   B".singleLineTitle())
    }

    @Test
    fun `首尾空白与换行去掉`() {
        assertEquals("标题", "\n  标题  \n".singleLineTitle())
        assertEquals("", "\n\n   ".singleLineTitle())
        val nullTitle: String? = null
        assertEquals("", nullTitle.singleLineTitle())
    }

    @Test
    fun `没有换行的普通标题原样返回`() {
        assertEquals("アナイクス", "アナイクス".singleLineTitle())
        assertEquals("October 5th, 2026", "October 5th, 2026".singleLineTitle())
    }

    @Test
    fun `全角空格不折`() {
        // U+3000 不是换行，不会触发 autosize 那条「整段排进 maxLines」判据，别顺手改掉它。
        assertEquals("神子　八重", "神子　八重".singleLineTitle())
    }
}
