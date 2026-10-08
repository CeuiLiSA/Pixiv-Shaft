package ceui.pixiv.ui.download

import ceui.lisa.R
import ceui.lisa.core.DownloadItem.DownloadState.DOWNLOADING
import ceui.lisa.core.DownloadItem.DownloadState.FAILED
import ceui.lisa.core.DownloadItem.DownloadState.INIT
import ceui.lisa.core.DownloadItem.DownloadState.PAUSED
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具栏操作按钮判据测试 —— 图标、标题与执行动作同源，
 * 覆盖全 FAILED 态重试、非全 FAILED 保持暂停/继续等场景。
 */
class ActiveToolbarActionTest {

    /** INIT 默认按「会被派发」构造（泵开着）；没人派发的等待项单独用 [PauseToggleItem] 写。 */
    private fun items(vararg states: Int) = states.map { PauseToggleItem(it, willDispatch = it == INIT) }

    @Test
    fun testOnlyFailedShowsRetryActionAndResources() {
        val action = resolveActiveToolbarAction(
            items = items(FAILED, FAILED),
            queuePaused = false,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.RETRY, action)
        assertEquals(R.drawable.ic_v3_restart, action.iconRes)
        assertEquals(R.string.dlmgr_queue_action_retry_failed, action.titleRes)
    }

    @Test
    fun testOnlyFailedEvenWhenQueuePausedShowsRetryAction() {
        val action = resolveActiveToolbarAction(
            items = items(FAILED),
            queuePaused = true,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.RETRY, action)
    }

    @Test
    fun testFailedWithUgoiraShowsPauseAction() {
        val action = resolveActiveToolbarAction(
            items = items(FAILED),
            queuePaused = false,
            hasUgoiraInFlight = true,
        )
        assertEquals(ActiveToolbarAction.PAUSE, action)
        assertEquals(R.drawable.ic_v3_pause_all_24, action.iconRes)
        assertEquals(R.string.dlmgr_active_action_pause_all, action.titleRes)
    }

    @Test
    fun testMixedFailedAndDownloadingShowsPauseAction() {
        val action = resolveActiveToolbarAction(
            items = items(FAILED, DOWNLOADING),
            queuePaused = false,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.PAUSE, action)
    }

    @Test
    fun testMixedFailedAndWaitingShowsPauseAction() {
        val action = resolveActiveToolbarAction(
            items = items(FAILED, INIT),
            queuePaused = false,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.PAUSE, action)
    }

    @Test
    fun testMixedFailedAndUndispatchedWaitingShowsResumeAction() {
        // 仅 Wi-Fi 在蜂窝上被 parkForNetwork 熄火的等待项：与 shouldResumeAll 同口径算「可继续」。
        val action = resolveActiveToolbarAction(
            items = listOf(PauseToggleItem(FAILED, false), PauseToggleItem(INIT, willDispatch = false)),
            queuePaused = false,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.RESUME, action)
    }

    @Test
    fun testMixedFailedAndPausedShowsResumeAction() {
        val action = resolveActiveToolbarAction(
            items = items(FAILED, PAUSED),
            queuePaused = false,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.RESUME, action)
        assertEquals(R.drawable.ic_v3_resume_all_24, action.iconRes)
        assertEquals(R.string.dlmgr_active_action_resume_all, action.titleRes)
    }

    @Test
    fun testAllPausedShowsResumeAction() {
        val action = resolveActiveToolbarAction(
            items = items(PAUSED, PAUSED),
            queuePaused = false,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.RESUME, action)
    }

    @Test
    fun testQueuePausedWithNoActiveWorkShowsResumeAction() {
        val action = resolveActiveToolbarAction(
            items = emptyList(),
            queuePaused = true,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.RESUME, action)
    }

    @Test
    fun testEmptyQueueShowsPauseAction() {
        val action = resolveActiveToolbarAction(
            items = emptyList(),
            queuePaused = false,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.PAUSE, action)
    }
}