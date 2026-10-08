package ceui.pixiv.ui.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量队列 tab「暂停 / 继续」按钮的方向判据 —— 文案与点击动作共用这一份口径，
 * 纯 JVM，不碰 Android / Settings / DB。
 */
class QueuePauseToggleTest {

    @Test fun `模式2 有排队行且被闸门hold 时显示继续`() {
        // 回归点：旧逻辑只看队列 pausedFlow（模式 2 下是 false）→ 显示「暂停」，
        // 点一下还是 no-op 的 pause()，要两次才开跑。
        assertTrue(
            shouldResumeQueue(hasActiveWork = true, queuePaused = false, queueRunning = false)
        )
    }

    @Test fun `模式1 蜂窝上被闸门hold 时显示继续`() {
        // 仅 Wi-Fi 在蜂窝上：autoStartAllowed()=false → queueRunning=false → 显示「继续」，
        // 一点即走 resumeByUser()（用户触发的操作忽略网络状态）。
        assertTrue(
            shouldResumeQueue(hasActiveWork = true, queuePaused = false, queueRunning = false)
        )
    }

    @Test fun `有活且在跑时显示暂停`() {
        assertFalse(
            shouldResumeQueue(hasActiveWork = true, queuePaused = false, queueRunning = true)
        )
    }

    @Test fun `用户暂停时显示继续`() {
        assertTrue(
            shouldResumeQueue(hasActiveWork = true, queuePaused = true, queueRunning = false)
        )
        // 没有待办行时也允许解除暂停（与工具栏 queuePaused 口径一致）
        assertTrue(
            shouldResumeQueue(hasActiveWork = false, queuePaused = true, queueRunning = false)
        )
    }

    @Test fun `空态维持暂停 点了是no-op`() {
        assertFalse(
            shouldResumeQueue(hasActiveWork = false, queuePaused = false, queueRunning = false)
        )
        assertFalse(
            shouldResumeQueue(hasActiveWork = false, queuePaused = false, queueRunning = true)
        )
    }

    @Test fun `只剩失败项时维持暂停 不替用户重试`() {
        // 与「正在下载」tab 工具栏 shouldResumeAll 同一口径：失败行要不要重试交给
        // 队列 tab 的「重试失败」，不在这个按钮里替用户拿主意。模式 2 下队列虽被闸门
        // hold（queueRunning=false），但没有「活」也不该显示「继续」。
        assertFalse(
            shouldResumeQueue(hasActiveWork = false, queuePaused = false, queueRunning = false)
        )
    }
}