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

    @Test
    fun testOnlyFailedShowsRetryActionAndResources() {
        val action = resolveActiveToolbarAction(
            states = listOf(FAILED, FAILED),
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
            states = listOf(FAILED),
            queuePaused = true,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.RETRY, action)
    }

    @Test
    fun testFailedWithUgoiraShowsPauseAction() {
        val action = resolveActiveToolbarAction(
            states = listOf(FAILED),
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
            states = listOf(FAILED, DOWNLOADING),
            queuePaused = false,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.PAUSE, action)
    }

    @Test
    fun testMixedFailedAndWaitingShowsPauseAction() {
        val action = resolveActiveToolbarAction(
            states = listOf(FAILED, INIT),
            queuePaused = false,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.PAUSE, action)
    }

    @Test
    fun testMixedFailedAndPausedShowsResumeAction() {
        val action = resolveActiveToolbarAction(
            states = listOf(FAILED, PAUSED),
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
            states = listOf(PAUSED, PAUSED),
            queuePaused = false,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.RESUME, action)
    }

    @Test
    fun testQueuePausedWithNoActiveWorkShowsResumeAction() {
        val action = resolveActiveToolbarAction(
            states = emptyList(),
            queuePaused = true,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.RESUME, action)
    }

    @Test
    fun testEmptyQueueShowsPauseAction() {
        val action = resolveActiveToolbarAction(
            states = emptyList(),
            queuePaused = false,
            hasUgoiraInFlight = false,
        )
        assertEquals(ActiveToolbarAction.PAUSE, action)
    }
}