package ceui.pixiv.ui.download

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import ceui.lisa.R
import ceui.lisa.core.DownloadItem.DownloadState.FAILED
import ceui.lisa.core.DownloadItem.DownloadState.PAUSED

/**
 * 「正在下载」列表卡片右侧那颗操作按钮该显示哪一面、点下去该执行什么动作 ——
 * 纯函数，便于单测与 UI 保持同一口径（图标与执行动作同源）。
 *
 * 历史背景（接续 PR #1202 遗留）：
 * 旧实现只看 [ceui.lisa.core.DownloadItem.isPaused]，在 FAILED 行上 isPaused=false，
 * 导致错误渲染为「暂停」图标、点下去执行 stopOne()。
 *
 * 口径：
 *   - FAILED 态：显示重试图标（既不是暂停也不是继续），点击动作重新触发下载（startOne）；
 *   - 暂停态（isPaused 或 state == PAUSED）：显示继续图标（play），点击动作继续（startOne）；
 *   - 运行/等待态：显示暂停图标（pause），点击动作暂停（stopOne）。
 */
internal enum class ActiveRowAction(
    @DrawableRes val iconRes: Int,
    @StringRes val contentDescriptionRes: Int,
) {
    RETRY(R.drawable.ic_v3_restart, R.string.retry),
    RESUME(R.drawable.ic_baseline_play_arrow_24, R.string.dlmgr_queue_action_resume),
    PAUSE(R.drawable.ic_baseline_pause_24, R.string.dlmgr_queue_action_pause);
}

internal fun resolveActiveRowAction(state: Int, isPaused: Boolean): ActiveRowAction {
    return when {
        state == FAILED -> ActiveRowAction.RETRY
        isPaused || state == PAUSED -> ActiveRowAction.RESUME
        else -> ActiveRowAction.PAUSE
    }
}