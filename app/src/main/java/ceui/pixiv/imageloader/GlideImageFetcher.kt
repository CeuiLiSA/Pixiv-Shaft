package ceui.pixiv.imageloader

import android.os.SystemClock
import ceui.lisa.activities.Shaft
import ceui.lisa.http.ImageHostManager
import ceui.lisa.utils.GlideUrlChild
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * [ImageFetcher] 的默认实现:复用 Glide 抓取(自带磁盘缓存 + 通过 [GlideUrlChild] 带上 Pixiv referer/UA 头),
 * 进度来自 [Shaft.getDownloadProgress]:Glide 的 OkHttp 客户端在 [Shaft] 里装了它的拦截器,
 * 订阅必须去同一个实例。
 *
 * 设计要点:
 * - 进度订阅是强引用表,注册和解除锁死在同一个 `finally` 里;协程被取消也一样会解除,
 *   所以不会像 ui/task 那样堆积监听器,也不会有「GC 把弱键收走、进度条半路不动」的窗口。
 * - 阻塞的 `FutureTarget.get()` 放进可中断的 [runInterruptible];协程取消能真正打断下载、
 *   `finally` 里 `cancel(true)` 释放 Glide 请求与线程,而不是像 ui/task 那样一路 `.get()` 干等到底。
 *
 * 日志:`fetch DONE` 会打出 `source`(MEMORY_CACHE/DATA_DISK_CACHE/RESOURCE_DISK_CACHE/REMOTE)与耗时,
 * 这是判断「B/C 是否命中共享缓存 vs 真的走了一次网络」最直接的信号。
 */
object GlideImageFetcher : ImageFetcher {

    override suspend fun fetch(url: String, onProgress: (Int) -> Unit): File {
        val shortUrl = url.substringAfterLast('/')

        // issue #865: the actual network request goes to whatever host
        // GlideUrlChild rewrites `url` to (Pixiv / pixiv.cat / custom). Subscribe
        // against that same rewritten url — ProgressTracker keys on the url the
        // request was issued with, so keying on the raw url would drop progress
        // updates in non-PIXIV modes. rewrite() is a no-op / idempotent in PIXIV.
        val requestUrl = ImageHostManager.rewrite(url)

        // 订阅用 use 锁在本作用域里:submit() 万一抛异常也不会在强引用表里留下一条孤儿订阅。
        return (Shaft.getContext() as Shaft).downloadProgress.track(requestUrl) { progress ->
            // 100% 只由上层在真正拿到文件那一刻置终态(见 [ImageFetcher.fetch] 的契约),这里只推 0..99。
            // 总长未知(chunked)时没有百分比可报,让进度条停在上一个值。
            val percent = progress.percent ?: return@track
            if (!progress.isDone) onProgress(percent.coerceIn(0, 99))
        }.use { subscription ->
            // 捕获 Glide 命中的数据源,仅用于日志分析(缓存命中 vs 走网络)。
            var dataSource: DataSource? = null
            val future = Glide.with(Shaft.getContext())
                .asFile()
                .load(GlideUrlChild(url))
                .listener(object : RequestListener<File> {
                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<File>, isFirstResource: Boolean
                    ): Boolean = false

                    override fun onResourceReady(
                        resource: File, model: Any, target: Target<File>?,
                        source: DataSource, isFirstResource: Boolean
                    ): Boolean {
                        dataSource = source
                        return false
                    }
                })
                .submit()

            val startMs = SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) {
                try {
                    val file = runInterruptible { future.get() }
                    val elapsed = SystemClock.elapsedRealtime() - startMs
                    Timber.d("[ImgV3] fetch DONE url=$shortUrl source=${dataSource?.name} ms=$elapsed size=${file.length()}")
                    file
                } finally {
                    // 先停回调再取消 Glide:取消引发的最后几次 read 不该再戳到已经放弃的页面。
                    subscription.close()
                    if (!future.isDone) future.cancel(true)
                }
            }
        }
    }
}
