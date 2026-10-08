package ceui.pixiv.ui.download

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import ceui.lisa.R
import ceui.lisa.core.DownloadItem.DownloadState.FAILED

/**
 * 「正在下载」页顶部工具栏右侧操作按钮呈现与动作目标 —— 纯函数，便于单测与 UI 保持同一口径。
 *
 * 口径：
 *   1. 活跃列表里只剩 FAILED 项（且没有动图在飞）→ 显示**重试失败**，点击只对 FAILED 项下发启动；
 *   2. 其余情况沿用 [shouldResumeAll] 的暂停 / 继续方向（含「没人派发的等待项算可继续」），
 *      不在这里另算一套。
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
    items: List<PauseToggleItem>,
    queuePaused: Boolean,
    hasUgoiraInFlight: Boolean,
): ActiveToolbarAction = when {
    !hasUgoiraInFlight && items.isNotEmpty() && items.all { it.state == FAILED } -> ActiveToolbarAction.RETRY
    shouldResumeAll(items, queuePaused, hasUgoiraInFlight) -> ActiveToolbarAction.RESUME
    else -> ActiveToolbarAction.PAUSE
}
