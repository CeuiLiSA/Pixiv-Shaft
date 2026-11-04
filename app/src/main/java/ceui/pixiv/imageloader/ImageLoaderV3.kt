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
 * 显式指明原图与非原图通道：
 * - 原图请求通过 [obtainOriginal] 或传 `isOriginal = true` 显式路由到专用断点续传加载器；
 * - 非原图请求走通用 Glide 加载，不污染原图缓存池。
 */
object ImageLoaderV3 {

    /** 取(或创建)一个共享加载任务。 */
    @JvmStatic
    fun obtain(request: ImageRequest, autoStart: Boolean = true): ImageLoadTask =
        ImageTaskRegistry.obtain(request, autoStart)

    /** 便捷重载:直接用 url 取任务。 */
    @JvmStatic
    @JvmOverloads
    fun obtain(
        url: String,
        name: String = url.substringAfterLast('/'),
        autoStart: Boolean = true,
        isOriginal: Boolean = false,
    ): ImageLoadTask = obtain(ImageRequest(url, name, isOriginal), autoStart)

    /** 显式声明 isOriginal 的便捷重载。 */
    @JvmStatic
    @JvmOverloads
    fun obtain(
        url: String,
        isOriginal: Boolean,
        autoStart: Boolean = true,
    ): ImageLoadTask = obtain(ImageRequest(url, isOriginal = isOriginal), autoStart)

    /** 获取原图任务入口。 */
    @JvmStatic
    @JvmOverloads
    fun obtainOriginal(
        url: String,
        autoStart: Boolean = true,
    ): ImageLoadTask = obtain(ImageRequest(url, isOriginal = true), autoStart)

    /** 无副作用窥探某 url 已下好的文件(占位链/缓存命中用)。 */
    @JvmStatic
    @JvmOverloads
    fun peekFile(url: String, isOriginal: Boolean = false): File? =
        ImageTaskRegistry.peekFile(url, isOriginal)

    /**
     * 无副作用地探一次「这个 url 现在能不能**不联网**拿到文件」，供批量写盘场景先分拣再决定并发。
     *
     * 两层，成本从低到高：
     * 1. [peekFile]：进程内共享任务已成功 → 直接给文件，零 IO；[isOriginal] 时再查原图独立缓存池；
     * 2. 非原图：Glide 磁盘缓存的 cache-only 请求：命中给文件，**未命中立即失败、不发网络请求**
     *    （与 [ceui.lisa.adapters.IllustAdapter] 拿 large 当占位时同一手法）。原图不进 Glide，不查这一层。
     *
     * 请求形状刻意与 [GlideImageFetcher.fetch] 对齐（`asFile()` + [GlideUrlChild]）：缓存键一致，
     * 「探到有」才等价于「fetch 会命中」。换成 asBitmap 之类会因变换后的缓存键不同而系统性假阴性。
     *
     * ⚠️ 只当**乐观前置**用：返回 null 只说明「这次按联网路径处理」，不保证真的会走网络
     * （fetch 自己还会命中磁盘缓存）；返回文件后该文件仍可能在复制前被缓存淘汰。任何调用方
     * 都不得把它当正确性依据，只能拿它排并发。
     */
    @JvmStatic
    @JvmOverloads
    suspend fun peekCachedFile(url: String, isOriginal: Boolean = false): File? {
        peekFile(url, isOriginal)?.let { return it }
        if (isOriginal) {
            return null
        }
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
