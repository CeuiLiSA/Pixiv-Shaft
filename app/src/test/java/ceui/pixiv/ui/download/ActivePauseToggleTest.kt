package ceui.pixiv.ui.download

import ceui.lisa.core.DownloadItem.DownloadState.DOWNLOADING
import ceui.lisa.core.DownloadItem.DownloadState.FAILED
import ceui.lisa.core.DownloadItem.DownloadState.INIT
import ceui.lisa.core.DownloadItem.DownloadState.PAUSED
import ceui.lisa.core.DownloadItem.DownloadState.SUCCESS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右上角「全部暂停 / 全部继续」的方向判据 —— 图标与点击动作共用这一份口径，纯 JVM，
 * 不碰 Android / Settings。
 */
class ActivePauseToggleTest {

    @Test fun `模式2 全是用户暂停项时显示继续`() {
        // 回归点：旧逻辑只看队列 pausedFlow（模式 2 下是 false）→ 显示「全部暂停」，
        // 点一下还是 no-op 的 stopAll()，用户看不到任何开始入口。
        assertTrue(
            shouldResumeAll(listOf(PAUSED, PAUSED), queuePaused = false, hasUgoiraInFlight = false)
        )
    }

    @Test fun `还有页在传或排队时显示暂停`() {
        assertFalse(shouldResumeAll(listOf(DOWNLOADING, PAUSED), false, false))
        assertFalse(shouldResumeAll(listOf(INIT, PAUSED), false, false))
    }

    @Test fun `仅WiFi 在蜂窝上的等待项属于活 不是暂停`() {
        // INIT（等待态）有自动唤醒源（回 Wi-Fi 会接续）。若把它算成「可以继续」，
        // 按钮会显示「全部继续」，一点就把 wifi-only 的项在蜂窝上强启了。
        assertFalse(shouldResumeAll(listOf(INIT), false, false))
    }

    @Test fun `动图在飞时显示暂停`() {
        // 动图不在 Manager.content 里，但「全部暂停」同样会掐掉它 → 算「有活」。
        assertFalse(shouldResumeAll(listOf(PAUSED), false, true))
    }

    @Test fun `队列整体暂停时显示继续`() {
        assertTrue(shouldResumeAll(emptyList(), true, false))
    }

    @Test fun `空态与只剩失败项维持暂停 不替用户重试`() {
        assertFalse(shouldResumeAll(emptyList(), false, false))
        assertFalse(shouldResumeAll(listOf(FAILED, SUCCESS), false, false))
    }
}