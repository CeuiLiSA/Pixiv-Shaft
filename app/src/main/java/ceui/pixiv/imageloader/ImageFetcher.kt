package ceui.pixiv.imageloader

import java.io.File

/**
 * 图片字节来源的抽象:给定 url,产出本地文件,并在下载途中回报进度。
 *
 * 这是 V3 加载系统与「用什么去抓字节」之间**唯一的接缝**。当前实现是 [GlideImageFetcher]
 * (复用 Glide 的磁盘缓存与 OkHttp 通道 + :progressmanager 的进度),但 [ImageLoadTask] 只依赖本接口、
 * 不认识 Glide/ProgressTracker —— 于是既能替换实现(如本地 SAF/txt 封面源),也能在单测里注入假实现。
 *
 * 这正是 ui/task 的反面:那里 `LoadTask` 把 Glide、`ProgressManager`、`Shaft` 全焊在一个类里,
 * `DownloadTask` 又靠继承 `LoadTask` 复用下载,逼得缓存池必须 `is DownloadTask` 特判 + 驱逐替换。
 */
interface ImageFetcher {

    /**
     * 下载 [url] 到本地文件。[onProgress] 在下载途中被回调,携带 0..99 的百分比
     * (100% 只在真正拿到文件那一刻由上层置终态,避免「进度到头图还没出来」)。
     *
     * 协程被取消时,实现应尽快中断底层请求、释放线程,并让 [kotlinx.coroutines.CancellationException] 透传。
     */
    suspend fun fetch(url: String, onProgress: (percent: Int) -> Unit): File
}
