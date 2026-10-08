package ceui.pixiv.ui.download

import ceui.lisa.core.DownloadItem.DownloadState.DOWNLOADING
import ceui.lisa.core.DownloadItem.DownloadState.INIT
import ceui.lisa.core.DownloadItem.DownloadState.PAUSED

/**
 * 「正在下载」tab 右上角那颗按钮该显示哪一面 —— 纯函数，UI 与单测共用同一份口径。
 *
 * 旧实现只看 [ceui.pixiv.ui.bulk.QueueDownloadManager.pausedFlow]（**批量队列**的暂停标志），
 * 于是模式 2「不自动下载」下必然错：内容列表里全是等用户手动启动的暂停项（入列即 paused），
 * 而队列标志是 false —— 按钮一直显示「全部暂停」图标，点一下还是 no-op 的 `stopAll()`，
 * 用户看不到任何「开始」入口，要启动得先点一次暂停、再点一次继续。
 *
 * 口径（图标与点击动作必须同源，不许各算一套）：
 *   - 还有「会自己推进的活」（在传 / 会被派发的等待项）或动图在飞 → 显示**全部暂停**；
 *   - 否则只要还有暂停项、没人派发的等待项、或队列整体暂停 → 显示**全部继续**；
 *   - 都没有（空列表 / 只剩失败项）→ 维持**全部暂停**：要不要重试失败的页由队列 tab 的
 *     「重试失败」决定，不在这个按钮里替用户拿主意。
 *
 * 「没人派发的等待项」= INIT 且 [ceui.lisa.core.Manager.willDispatch] 为 false：仅 Wi-Fi 在蜂窝上被
 * parkForNetwork 熄火的、冷启动恢复后泵还没开的。它们只能等用户（或回到 Wi-Fi），按钮给出一键
 * 继续 —— 与详情页 FAB（Resume）、批量队列 tab（shouldResumeQueue）同一口径；若显示「全部暂停」，
 * 点下去会把它们打成用户暂停，回到 Wi-Fi 也不再自动接续。
 *
 * @param items 每一项的 [PauseToggleItem]。
 * @param queuePaused [ceui.pixiv.ui.bulk.QueueDownloadManager.isPaused]
 * @param hasUgoiraInFlight 有动图任务在飞。动图不走 [ceui.lisa.core.Manager.content]，
 *        但「全部暂停」同样会掐掉它们（见 QueueDownloadManager.pause），故算「有活」。
 */
internal fun shouldResumeAll(
    items: List<PauseToggleItem>,
    queuePaused: Boolean,
    hasUgoiraInFlight: Boolean,
): Boolean {
    if (hasUgoiraInFlight) return false
    if (items.any { it.state == DOWNLOADING || (it.state == INIT && it.willDispatch) }) return false
    return queuePaused || items.any { it.state == PAUSED || it.state == INIT }
}

/**
 * [shouldResumeAll] 的单项输入。
 *
 * @param state [ceui.lisa.core.DownloadItem.getState]（paused 已折叠成 PAUSED）。
 * @param willDispatch 仅对 INIT 有意义：这条等待项会不会被派发出去（[ceui.lisa.core.Manager.willDispatch]）。
 */
internal data class PauseToggleItem(val state: Int, val willDispatch: Boolean)
