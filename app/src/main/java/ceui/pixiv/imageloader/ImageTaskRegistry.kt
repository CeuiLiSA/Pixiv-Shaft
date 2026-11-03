package ceui.pixiv.imageloader

import androidx.annotation.VisibleForTesting
import ceui.lisa.activities.Shaft
import ceui.pixiv.cache.OriginalImageCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import java.io.File

/**
 * 进程级的共享任务注册表 —— V3 图片加载系统「共享 + 保留」的核心。
 *
 * 一张 `url -> [ImageLoadTask]` 的 LRU 表:
 * - **共享**:同一个 url 只有一个任务,详情页 B 和大图页 C 请求同一 url 时命中同一实例,
 *   进度/结果自然共享,不会各下一次。
 * - **保留**:本对象是进程级单例,任务不随 fragment/activity 销毁。B 返回列表再进 B/C,
 *   命中的还是原任务,进度和已下文件都在(直到被 LRU 淘汰)。
 *
 * 原图与缩略图分流（由调用方在 [ImageRequest.isOriginal] 显式告知）：
 * - 原图：走 [ResumableOkHttpFetcher]，支持断点续传，落在独立原图缓存池；
 * - 缩略图（square / medium / large 等）：继续走 [GlideImageFetcher]，复用 Glide 磁盘缓存。
 */
object ImageTaskRegistry {

    private const val MAX_CACHE_SIZE = 256

    /** 下载协程的宿主作用域,进程级、不随 UI 销毁。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @VisibleForTesting
    internal var customFetcherResolver: ((ImageRequest) -> ImageFetcher)? = null

    private fun resolveFetcher(request: ImageRequest): ImageFetcher {
        customFetcherResolver?.let { return it(request) }
        return if (request.isOriginal) ResumableOkHttpFetcher else GlideImageFetcher
    }

    // 访问序 LinkedHashMap 实现 LRU
    private val taskMap: LinkedHashMap<String, ImageLoadTask> =
        object : LinkedHashMap<String, ImageLoadTask>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ImageLoadTask>?): Boolean {
                val evict = size > MAX_CACHE_SIZE
                if (evict && eldest != null) {
                    Timber.d("[ImgV3] registry EVICT url=${eldest.key.substringAfterLast('/')} state=${eldest.value.state.value} pool=$size")
                }
                return evict
            }
        }

    /**
     * 取(或创建)某个请求对应的共享任务。同一 [ImageRequest.key] 恒返回同一实例。
     * @param autoStart 取到后是否立即启动下载(瀑布流这类可传 false,等真正需要时再 start)。
     */
    @Synchronized
    fun obtain(request: ImageRequest, autoStart: Boolean = true): ImageLoadTask {
        val shortUrl = request.url.substringAfterLast('/')
        val existing = taskMap[request.key]
        if (existing != null) {
            Timber.d("[ImgV3] registry REUSE url=$shortUrl state=${existing.state.value} pool=${taskMap.size}")
            if (autoStart) existing.start()
            return existing
        }

        val initialFile = if (request.isOriginal) {
            runCatching { Shaft.getContext() }.getOrNull()?.let { ctx ->
                OriginalImageCacheManager.find(ctx, request.url)
            }
        } else {
            null
        }

        val fetcher = resolveFetcher(request)
        val task = ImageLoadTask(request, scope, fetcher, initialFile = initialFile)
        taskMap[request.key] = task
        Timber.d("[ImgV3] registry CREATE url=$shortUrl isOriginal=${request.isOriginal} autoStart=$autoStart hasInitial=${initialFile != null} pool=${taskMap.size}")
        if (autoStart) task.start()
        return task
    }

    /** 无副作用地窥探某 url 是否已有可用的下载结果(用于占位链/缓存命中判断)。 */
    @Synchronized
    fun peekFile(url: String, isOriginal: Boolean = false): File? {
        val file = taskMap[url]?.currentFile
        if (file != null && file.exists() && file.length() > 0) {
            return file
        }

        if (isOriginal) {
            val cached = runCatching { Shaft.getContext() }.getOrNull()?.let { ctx ->
                OriginalImageCacheManager.find(ctx, url)
            }
            if (cached != null && cached.exists() && cached.length() > 0) {
                return cached
            }
        }

        return null
    }

    /** Lookup only: saving can join an existing display task without starting a new fetch. */
    @Synchronized
    internal fun peekTask(url: String): ImageLoadTask? = taskMap[url]

    @Synchronized
    fun remove(url: String) {
        val removed = taskMap.remove(url)
        Timber.d("[ImgV3] registry REMOVE url=${url.substringAfterLast('/')} found=${removed != null} pool=${taskMap.size}")
    }
}
