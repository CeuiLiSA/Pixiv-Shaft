package ceui.pixiv.imageloader

import androidx.annotation.WorkerThread
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.GlideUrlChild
import com.bumptech.glide.Glide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.function.IntConsumer

/**
 * V3 图片加载系统的对外门面(entry point)。
 *
 * 设计目标:详情页 B 与大图页 C 请求同一张图(同一 url / 同分辨率)时,共享**同一个下载任务**——
 * 同一条进度、同一份结果,不各下一次;且任务由进程级 [ImageTaskRegistry] 持有,B 返回列表再进 B/C,
 * 进度与已下文件都保留。瀑布流 A 分辨率低、url 不同,天然是另一个任务,不与 B/C 共享(也无需共享)。
 *
 * 抓取与解码全程复用 Glide,进度来自 :progressmanager 的 ProgressTracker(挂在 Shaft 的 OkHttp 客户端上)。
 *
 * 典型用法(渲染层的 ImageView 绑定扩展后续加入本包):
 * ```
 * val task = ImageLoaderV3.obtain(url)            // C:original;B:large 或 original
 * // 观察 task.state 更新进度条,Success 时把 task.currentFile 交给 Glide/Sketch 渲染
 * ```
 */
object ImageLoaderV3 {

    /** 取(或创建)一个共享加载任务。 */
    @JvmStatic
    fun obtain(request: ImageRequest, autoStart: Boolean = true): ImageLoadTask =
        ImageTaskRegistry.obtain(request, autoStart)

    /** 便捷重载:直接用 url 取任务(Java 侧 `ImageLoaderV3.obtain(url)` 即可)。 */
    @JvmStatic
    @JvmOverloads
    fun obtain(
        url: String,
        name: String = url.substringAfterLast('/'),
        autoStart: Boolean = true,
    ): ImageLoadTask = obtain(ImageRequest(url, name), autoStart)

    /** 无副作用窥探某 url 已下好的文件(占位链/缓存命中用)。 */
    @JvmStatic
    fun peekFile(url: String): File? = ImageTaskRegistry.peekFile(url)

    /**
     * 无副作用地探一次「这个 url 现在能不能**不联网**拿到文件」，供批量写盘场景先分拣再决定并发。
     *
     * 两层，成本从低到高：
     * 1. [peekFile]：进程内共享任务已成功 → 直接给文件，零 IO；
     * 2. Glide 磁盘缓存的 cache-only 请求：命中给文件，**未命中立即失败、不发网络请求**
     *    （与 [ceui.lisa.adapters.IllustAdapter] 拿 large 当占位时同一手法）。
     *
     * 请求形状刻意与 [GlideImageFetcher.fetch] 对齐（`asFile()` + [GlideUrlChild]）：缓存键一致，
     * 「探到有」才等价于「fetch 会命中」。换成 asBitmap 之类会因变换后的缓存键不同而系统性假阴性。
     *
     * ⚠️ 只当**乐观前置**用：返回 null 只说明「这次按联网路径处理」，不保证真的会走网络
     * （fetch 自己还会命中磁盘缓存）；返回文件后该文件仍可能在复制前被缓存淘汰。任何调用方
     * 都不得把它当正确性依据，只能拿它排并发。
     */
    @JvmStatic
    suspend fun peekCachedFile(url: String): File? {
        peekFile(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            val future = Glide.with(Shaft.getContext())
                .asFile()
                .load(GlideUrlChild(url))
                .onlyRetrieveFromCache(true)
                .submit()
            try {
                runInterruptible { future.get() }.takeIf { it.isFile && it.length() > 0 }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // 未命中是常态：cache-only 的 miss 就是一次「不适用」的答复，不是错误。
                null
            } finally {
                if (!future.isDone) future.cancel(true)
            }
        }
    }

    /**
     * Blocking bridge for Manager's DownloadTask IO worker. Join the display fetch, then copy its
     * completed file through the normal save backend. A miss keeps Manager's own resumable path.
     * Interrupting this waiter cancels only its collection, never the process-owned image task.
     */
    @JvmStatic
    @WorkerThread
    @Throws(InterruptedException::class)
    fun awaitExistingFile(url: String, onProgress: IntConsumer): File? {
        val task = ImageTaskRegistry.peekTask(url) ?: return null
        when (task.state.value) {
            ImageLoadState.Idle, is ImageLoadState.Error -> return null
            else -> Unit
        }
        return awaitExistingFile(task, onProgress)
    }

    internal fun awaitExistingFile(task: ImageLoadTask, onProgress: IntConsumer): File {
        val url = task.request.url
        Timber.tag("SharedImageDownload").d(
            "JOIN image=%s state=%s", url.substringAfterLast('/'), task.state.value,
        )
        try {
            return runBlocking {
                task.awaitFile { onProgress.accept(it) }
            }.also {
                Timber.tag("SharedImageDownload").d(
                    "READY image=%s bytes=%d", url.substringAfterLast('/'), it.length(),
                )
            }
        } catch (cancelled: InterruptedException) {
            Timber.tag("SharedImageDownload").d("WAIT_CANCELLED image=%s", url.substringAfterLast('/'))
            throw cancelled
        } catch (cancelled: CancellationException) {
            Timber.tag("SharedImageDownload").d("WAIT_CANCELLED image=%s", url.substringAfterLast('/'))
            throw cancelled
        } catch (error: Exception) {
            Timber.tag("SharedImageDownload").w(error, "WAIT_FAILED image=%s", url.substringAfterLast('/'))
            throw error
        }
    }
}
