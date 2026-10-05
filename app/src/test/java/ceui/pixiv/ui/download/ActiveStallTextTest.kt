package ceui.pixiv.ui.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 活跃下载行的「断流」显示 —— 一条正在下载的项连续无字节进展超过门槛时，sizeText 从
 * 「已下 / 总长」换成 `已断流{已卡秒数}/{读超时秒数}s`，向用户说清是**对端不再吐数据**（这张图在
 * 链路上沉默了），而不是这条下载死了 / 客户端卡了。
 *
 * 三件事一起钉住：
 *  1. 模板与两个入参的**顺序**（先已卡秒数、后读超时秒数）—— 写反会变成「已断流10/3s」，
 *     视觉上还像句话，评审里极难发现；
 *  2. 分母来自运行时（[formatActiveStallText] 不写死 10）—— 直连 client 读超时是 30s，
 *     后续做「断流立即重连」把读超时改小时，这里必须跟着变；
 *  3. 读秒口径（[stallSecondsFor]）：**门槛 1s + 四舍五入** —— 读超时恰好在无字节进展满 10s 那刻
 *     结束这一发，floor 下「10」只存在于超时到标 FAILED 的几十毫秒里、基本看不到；四舍五入让它
 *     在 9.5s 就显示出来。
 */
class ActiveStallTextTest {

    // 注意：Kotlin 字符串里 `$d` 会被当成模板变量，`%1$d` 必须写成 `%1\$d`。
    private val zhTemplate = "已断流%1\$d/%2\$ds"

    /** 基本形态：已卡 3 秒 / 读超时 10 秒。 */
    @Test
    fun `已卡秒数在前读超时秒数在后`() {
        assertEquals("已断流3/10s", formatActiveStallText(zhTemplate, 3, 10))
    }

    /** 分母不硬编码：读超时 30s（直连）时如实显示 30，而不是钉死 10。 */
    @Test
    fun `分母跟随读超时不是写死的十`() {
        assertEquals("已断流1/30s", formatActiveStallText(zhTemplate, 1, 30))
        assertEquals("已断流2/5s", formatActiveStallText(zhTemplate, 2, 5))
    }

    /** 两个入参不能互换 —— 换过来就成「已断流10/3s」，本用例把它挡住。 */
    @Test
    fun `入参写反会被挡下`() {
        assertEquals("已断流3/10s", formatActiveStallText(zhTemplate, 3, 10))
        assertEquals("已断流10/3s", formatActiveStallText(zhTemplate, 10, 3))
    }

    /** 英文模板同样按「已卡 / 超时」顺序填充。 */
    @Test
    fun `英文模板按同一顺序填充`() {
        assertEquals("Stalled 5/10s", formatActiveStallText("Stalled %1\$d/%2\$ds", 5, 10))
    }

    /** 门槛：无字节进展不足 1s 不算断流（正常抖动），返回 null 即不显示。 */
    @Test
    fun `无进展不足一秒不报断流`() {
        assertNull(stallSecondsFor(0L))
        assertNull(stallSecondsFor(1L))
        assertNull(stallSecondsFor(999L))
    }

    /** 读秒四舍五入：9.5s 就要报 10 —— 读超时在 10s 那刻结束这一发，floor 根本来不及显示 10。 */
    @Test
    fun `读秒四舍五入 九点五秒即报十`() {
        assertEquals(1, stallSecondsFor(1000L))
        assertEquals(1, stallSecondsFor(1499L))
        assertEquals(2, stallSecondsFor(1500L))
        assertEquals(9, stallSecondsFor(9499L))
        assertEquals(10, stallSecondsFor(9500L))
        assertEquals(10, stallSecondsFor(9999L))
    }
}
