package ceui.pixiv.imageloader

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
 * 淘汰只是把任务移出表,不主动 cancel 正在跑的下载(协程挂在 [scope] 上会自己跑完);真正的图片
 * 字节缓存在 Glide 的磁盘缓存里,本表只持有轻量的状态与文件指针,所以可以放心缓存很多条。
 */
object ImageTaskRegistry {

    private const val MAX_CACHE_SIZE = 256

    /** 下载协程的宿主作用域,进程级、不随 UI 销毁。 */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 抓字节的实现,复用 Glide(见 [GlideImageFetcher])。通过 registry 统一交给每个任务,
     * 任务本身不认识 Glide/ProgressTracker。
     *
     * 不是可写的全局注入点:全项目(含单测)没有任何地方替换过它,一个「为了可测留的口子」
     * 若从未用上,剩下的只是一个谁都能改的进程级可变量。要测 [ImageLoadTask],直接 new 一个
     * 传假 [ImageFetcher] 即可,不必经过本表。
     */
    private val fetcher: ImageFetcher = GlideImageFetcher

    // 访问序 LinkedHashMap 实现 LRU
    private val taskMap: LinkedHashMap<String, ImageLoadTask> =
        object : LinkedHashMap<String, ImageLoadTask>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ImageLoadTask>?): Boolean {
                val evict = size > MAX_CACHE_SIZE
                if (evict && eldest != null) {
                    // 命中说明 256 上限被打满;若这条正在被别的页面观察,淘汰后再 obtain 会另起一份、无法复用。
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
            // REUSE = 共享命中(B/C 复用同一任务)。分析共享是否生效就看这条。
            Timber.d("[ImgV3] registry REUSE url=$shortUrl state=${existing.state.value} pool=${taskMap.size}")
            if (autoStart) existing.start()
            return existing
        }
        val task = ImageLoadTask(request, scope, fetcher)
        taskMap[request.key] = task
        Timber.d("[ImgV3] registry CREATE url=$shortUrl autoStart=$autoStart pool=${taskMap.size}")
        if (autoStart) task.start()
        return task
    }

    /** 无副作用地窥探某 url 是否已有可用的下载结果(用于占位链/缓存命中判断)。 */
    @Synchronized
    fun peekFile(url: String): File? {
        val file = taskMap[url]?.currentFile ?: return null
        return file.takeIf { it.exists() && it.length() > 0 }
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
