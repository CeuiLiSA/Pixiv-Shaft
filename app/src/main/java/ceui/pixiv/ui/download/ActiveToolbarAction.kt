package ceui.pixiv.ui.download

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import ceui.lisa.R
import ceui.lisa.core.DownloadItem.DownloadState.DOWNLOADING
import ceui.lisa.core.DownloadItem.DownloadState.FAILED
import ceui.lisa.core.DownloadItem.DownloadState.INIT
import ceui.lisa.core.DownloadItem.DownloadState.PAUSED

/**
 * 「正在下载」页顶部工具栏右侧操作按钮呈现与动作目标 —— 纯函数，便于单测与 UI 保持同一口径。
 *
 * 口径：
 *   1. 活跃队列中只有 FAILED 态时，显示重试图标并只对 FAILED 态下发启动；
 *   2. 若队列并非全部是 FAILED 态（或有动图在飞、有在传/排队项），保持全部暂停；
 *   3. 若无在传/排队项，且存在暂停项或队列整体暂停，保持全部继续；
 *   4. 空列表等兜底保持全部暂停。
 */
internal enum class ActiveToolbarAction(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
) {
    RETRY(R.drawable.ic_v3_restart, R.string.dlmgr_queue_action_retry_failed),
    RESUME(R.drawable.ic_v3_resume_all_24, R.string.dlmgr_active_action_resume_all),
    PAUSE(R.drawable.ic_v3_pause_all_24, R.string.dlmgr_active_action_pause_all);
}

internal fun resolveActiveToolbarAction(
    states: List<Int>,
    queuePaused: Boolean,
    hasUgoiraInFlight: Boolean,
): ActiveToolbarAction {
    // 1. 活跃队列中只有 FAILED 态时，显示重试图标
    if (states.isNotEmpty() && states.all { it == FAILED } && !hasUgoiraInFlight) {
        return ActiveToolbarAction.RETRY
    }

    // 2. 还有「未暂停的活」（在传 / 排队待派发）或动图在飞 → 全部暂停
    if (hasUgoiraInFlight) {
        return ActiveToolbarAction.PAUSE
    }
    if (states.any { it == DOWNLOADING || it == INIT }) {
        return ActiveToolbarAction.PAUSE
    }

    // 3. 有暂停项、或队列整体暂停 → 全部继续
    if (queuePaused || states.any { it == PAUSED }) {
        return ActiveToolbarAction.RESUME
    }

    // 4. 空态等兜底维持全部暂停
    return ActiveToolbarAction.PAUSE
}