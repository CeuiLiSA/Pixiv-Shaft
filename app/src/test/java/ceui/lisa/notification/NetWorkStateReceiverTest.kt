package ceui.lisa.notification

import ceui.lisa.notification.NetWorkStateReceiver.ACTION_NONE
import ceui.lisa.notification.NetWorkStateReceiver.ACTION_PARK
import ceui.lisa.notification.NetWorkStateReceiver.ACTION_WAKE
import ceui.lisa.notification.NetWorkStateReceiver.actionFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [NetWorkStateReceiver.actionFor]：只有「仅 Wi-Fi」模式、且真的进出 Wi-Fi 时才 park / 唤醒。
 */
class NetWorkStateReceiverTest {

    @Test fun `仅WiFi 离开 Wi-Fi 退回等待态`() {
        assertEquals(ACTION_PARK, actionFor(true, true, false))
    }

    @Test fun `仅WiFi 回到 Wi-Fi 自动接续`() {
        assertEquals(ACTION_WAKE, actionFor(true, false, true))
    }

    @Test fun `蜂窝内断流重连不 park 用户手动继续的下载`() {
        assertEquals(ACTION_NONE, actionFor(true, false, false))
    }

    @Test fun `Wi-Fi 换热点不重复踢 pump`() {
        assertEquals(ACTION_NONE, actionFor(true, true, true))
    }

    @Test fun `没有基线时按变化处理`() {
        assertEquals(ACTION_PARK, actionFor(true, null, false))
        assertEquals(ACTION_WAKE, actionFor(true, null, true))
    }

    @Test fun `无限制与不自动下载 切网什么都不做`() {
        assertEquals(ACTION_NONE, actionFor(false, true, false))
        assertEquals(ACTION_NONE, actionFor(false, false, true))
        assertEquals(ACTION_NONE, actionFor(false, null, true))
    }
}
