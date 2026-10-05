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
 *   - 还有「未暂停的活」（在传 / 排队待派发）或动图在飞 → 显示**全部暂停**；
 *   - 否则只要还有暂停项、或队列整体暂停 → 显示**全部继续**；
 *   - 两者都没有（空列表 / 只剩失败项）→ 维持**全部暂停**，点了是 no-op：要不要重试失败的
 *     页由队列 tab 的「重试失败」决定，不在这个按钮里替用户拿主意。
 *
 * @param states 每一项的 [ceui.lisa.core.DownloadItem.getState]：paused 已折叠成 PAUSED，
 *               所以 INIT 天然表示「未暂停的等待」（仅 Wi-Fi 在蜂窝上的那些项）——
 *               它们有自动唤醒源（回 Wi-Fi 会接续），属于「活」这一侧，不是暂停项。
 * @param queuePaused [ceui.pixiv.ui.bulk.QueueDownloadManager.isPaused]
 * @param hasUgoiraInFlight 有动图任务在飞。动图不走 [ceui.lisa.core.Manager.content]，
 *        但「全部暂停」同样会掐掉它们（见 QueueDownloadManager.pause），故算「有活」。
 */
internal fun shouldResumeAll(
    states: List<Int>,
    queuePaused: Boolean,
    hasUgoiraInFlight: Boolean,
): Boolean {
    if (hasUgoiraInFlight) return false
    if (states.any { it == DOWNLOADING || it == INIT }) return false
    return queuePaused || states.any { it == PAUSED }
}