package ceui.pixiv.ui.download

/**
 * 批量队列 tab 的「暂停 / 继续」按钮该显示哪一面 —— 纯函数，UI 与单测共用同一份口径。
 *
 * 旧实现只看 [ceui.pixiv.ui.bulk.QueueDownloadManager.pausedFlow]（**用户暂停**标志），
 * 于是模式 2「不自动下载」下必然错：队列不是被用户暂停，而是被自动闸门 hold
 * （paused=false、userForced=false、autoStartAllowed()=false），按钮仍显示「暂停」，
 * 点一下是 no-op 的 pause()，要启动得先点一次暂停、再点一次继续。
 *
 * 口径（文案与点击动作必须同源，不许各算一套）：
 *   - 队列被用户暂停 → 显示**继续**（哪怕当前没有待办行，也要让用户能解除暂停）；
 *   - 否则还有「未完成的活」（PENDING / DOWNLOADING 的行）且队列不会推进（被闸门
 *     hold）→ 显示**继续**；
 *   - 有活且队列会推进 → 显示**暂停**；
 *   - 空态 / 只剩 FAILED 行 → 维持**暂停**（点了是 no-op）：要不要重试失败行由队列
 *     tab 的「重试失败」决定，不在这里替用户拿主意 —— 与「正在下载」tab 工具栏
 *     shouldResumeAll 同一口径。
 *
 * @param hasActiveWork 队列里是否还有 PENDING / DOWNLOADING 的行。SUCCESS 会自动离开
 *        这个 tab，FAILED 留着等用户决定，两者都不算「活」。
 * @param queuePaused 用户是否主动暂停（[ceui.pixiv.ui.bulk.QueueDownloadManager.isPaused]）。
 * @param queueRunning 队列当前是否会推进：`!paused && (userForced || 自动闸门放行)`
 *        （[ceui.pixiv.ui.bulk.QueueDownloadManager.queueRunningFlow]）。
 */
internal fun shouldResumeQueue(
    hasActiveWork: Boolean,
    queuePaused: Boolean,
    queueRunning: Boolean,
): Boolean = queuePaused || (hasActiveWork && !queueRunning)