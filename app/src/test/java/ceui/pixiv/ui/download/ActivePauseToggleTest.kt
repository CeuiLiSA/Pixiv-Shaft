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

    private fun item(state: Int, willDispatch: Boolean = false) = PauseToggleItem(state, willDispatch)

    @Test fun `模式2 全是用户暂停项时显示继续`() {
        // 回归点：旧逻辑只看队列 pausedFlow（模式 2 下是 false）→ 显示「全部暂停」，
        // 点一下还是 no-op 的 stopAll()，用户看不到任何开始入口。
        assertTrue(
            shouldResumeAll(listOf(item(PAUSED), item(PAUSED)), queuePaused = false, hasUgoiraInFlight = false)
        )
    }

    @Test fun `还有页在传或会被派发的等待项时显示暂停`() {
        assertFalse(shouldResumeAll(listOf(item(DOWNLOADING), item(PAUSED)), false, false))
        assertFalse(shouldResumeAll(listOf(item(INIT, willDispatch = true), item(PAUSED)), false, false))
    }

    @Test fun `仅WiFi 在蜂窝上没人派发的等待项显示继续`() {
        // 与详情页 FAB（Resume）、批量队列 tab（shouldResumeQueue）同一口径：一键继续。
        // 若显示「全部暂停」，点下去会把这些等待项打成用户暂停，回到 Wi-Fi 也不再自动接续。
        assertTrue(shouldResumeAll(listOf(item(INIT, willDispatch = false)), false, false))
    }

    @Test fun `冷启动恢复后泵没开的等待项显示继续 混着在传的页仍显示暂停`() {
        assertTrue(shouldResumeAll(listOf(item(INIT), item(FAILED)), false, false))
        // 详情页单独放行了某个作品：它在传，其余等待项没人派发 —— 有活在跑，先给暂停。
        assertFalse(shouldResumeAll(listOf(item(DOWNLOADING), item(INIT)), false, false))
    }

    @Test fun `动图在飞时显示暂停`() {
        // 动图不在 Manager.content 里，但「全部暂停」同样会掐掉它 → 算「有活」。
        assertFalse(shouldResumeAll(listOf(item(PAUSED)), false, true))
    }

    @Test fun `队列整体暂停时显示继续`() {
        assertTrue(shouldResumeAll(emptyList(), true, false))
    }

    @Test fun `空态与只剩失败项维持暂停 不替用户重试`() {
        assertFalse(shouldResumeAll(emptyList(), false, false))
        assertFalse(shouldResumeAll(listOf(item(FAILED), item(SUCCESS)), false, false))
    }
}
