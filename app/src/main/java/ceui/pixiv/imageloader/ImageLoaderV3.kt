package ceui.pixiv.imageloader

import androidx.annotation.WorkerThread
import ceui.lisa.activities.Shaft
import ceui.lisa.utils.GlideUrlChild
import com.bumptech.glide.Glide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
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
                null
            } finally {
                if (!future.isDone) future.cancel(true)
            }
        }
    }

    /**
     * Blocking bridge for Manager's DownloadTask IO worker.
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
        val file = task.currentFile
        if (file != null) return file

        return kotlinx.coroutines.runBlocking {
            try {
                task.awaitFile { onProgress.accept(it) }
            } catch (ie: InterruptedException) {
                throw ie
            } catch (ce: CancellationException) {
                throw InterruptedException("download interrupted").apply { initCause(ce) }
            } catch (e: Exception) {
                null
            }
        }
    }
}
