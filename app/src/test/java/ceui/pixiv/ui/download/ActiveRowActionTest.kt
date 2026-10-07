package ceui.pixiv.ui.download

import ceui.lisa.R
import ceui.lisa.core.DownloadItem.DownloadState.DOWNLOADING
import ceui.lisa.core.DownloadItem.DownloadState.FAILED
import ceui.lisa.core.DownloadItem.DownloadState.INIT
import ceui.lisa.core.DownloadItem.DownloadState.PAUSED
import ceui.lisa.core.DownloadItem.DownloadState.SUCCESS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 卡片右侧操作按钮呈现与动作目标测试 —— 图标、无障碍描述与点击动作同源，
 * 杜绝 FAILED 态误显暂停或调用 stopOne。
 */
class ActiveRowActionTest {

    @Test
    fun testFailedStateShowsRetryActionAndResources() {
        val action = resolveActiveRowAction(state = FAILED, isPaused = false)
        assertEquals(ActiveRowAction.RETRY, action)
        assertEquals(R.drawable.ic_v3_restart, action.iconRes)
        assertEquals(R.string.retry, action.contentDescriptionRes)
    }

    @Test
    fun testFailedStateOverridesPausedFlag() {
        val action = resolveActiveRowAction(state = FAILED, isPaused = true)
        assertEquals(ActiveRowAction.RETRY, action)
    }

    @Test
    fun testPausedStateShowsResumeActionAndResources() {
        val actionFromState = resolveActiveRowAction(state = PAUSED, isPaused = false)
        assertEquals(ActiveRowAction.RESUME, actionFromState)
        assertEquals(R.drawable.ic_baseline_play_arrow_24, actionFromState.iconRes)
        assertEquals(R.string.dlmgr_queue_action_resume, actionFromState.contentDescriptionRes)

        val actionFromFlag = resolveActiveRowAction(state = INIT, isPaused = true)
        assertEquals(ActiveRowAction.RESUME, actionFromFlag)
    }

    @Test
    fun testDownloadingAndWaitingShowPauseActionAndResources() {
        val actionDownloading = resolveActiveRowAction(state = DOWNLOADING, isPaused = false)
        assertEquals(ActiveRowAction.PAUSE, actionDownloading)
        assertEquals(R.drawable.ic_baseline_pause_24, actionDownloading.iconRes)
        assertEquals(R.string.dlmgr_queue_action_pause, actionDownloading.contentDescriptionRes)

        val actionWaiting = resolveActiveRowAction(state = INIT, isPaused = false)
        assertEquals(ActiveRowAction.PAUSE, actionWaiting)
    }

    @Test
    fun testSuccessFallbackShowsPauseAction() {
        val actionSuccess = resolveActiveRowAction(state = SUCCESS, isPaused = false)
        assertEquals(ActiveRowAction.PAUSE, actionSuccess)
    }
}