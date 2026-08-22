package ceui.pixiv.ui.bulk

import ceui.loxia.Illust

/**
 * Ugoira 任务的可见进度阶段。下载链是单 zip + 同步编码，没有自然的字节级
 * % 进度可报；用 phase 让"正在下载" tab 的 cell 至少能显示当前在哪一步。
 *
 * 原本嵌在 [QueueDownloadManager] 内部，外部需要写
 * `QueueDownloadManager.UgoiraPhase` 才能引用。提到 top-level 后在同包里直接用
 * `UgoiraPhase`，跨包用 `import ceui.pixiv.ui.bulk.UgoiraPhase` 即可。
 */
enum class UgoiraPhase {
    QUEUED,         // 等 ugoiraEncodeSem 的许可（前面有别条 ugoira 在 encode）
    FETCH_META,     // getGifPackage 拿 zip url + frame delays
    DOWNLOAD_ZIP,   // 占 ugoira 总耗时大头的 zip 流式下载
    EXTRACT,        // unzip → cacheDir
    INTERPOLATE,    // RIFE AI 补帧(可选,开关开启且模型在位才走)
    ENCODE,         // AnimatedGifEncoder 编 GIF + commit 进 V3 WriteHandle（串行）
}

/** "正在下载" tab 渲染用的 ugoira 行快照（rowId 用 download_queue.id；bean 给缩略图 / 标题 / 详情跳转）。 */
data class UgoiraInFlight(
    val rowId: Long,
    val bean: Illust,
    val phase: UgoiraPhase,
)
