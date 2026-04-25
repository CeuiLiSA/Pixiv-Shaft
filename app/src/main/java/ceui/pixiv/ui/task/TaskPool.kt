package ceui.pixiv.ui.task

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import java.io.File

object TaskPool {

    private const val MAX_CACHE_SIZE = 256

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 使用 LinkedHashMap 来实现 LRU 缓存
    private val _taskMap: LinkedHashMap<String, LoadTask> =
        object : LinkedHashMap<String, LoadTask>(
            MAX_CACHE_SIZE,
            0.75f,
            true
        ) {
            override fun removeEldestEntry(eldest: Map.Entry<String, LoadTask>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }

    fun getLoadTask(namedUrl: NamedUrl, autoStart: Boolean = true): LoadTask {
        val shortUrl = namedUrl.url.substringAfterLast('/')
        val existing = _taskMap[namedUrl.url]
        if (existing != null) {
            // DownloadTask 占了坑位但没有可用结果 —— 不能直接启动它（会触发 saveImageToGallery），
            // 用一个纯 LoadTask 替换，仅负责加载图片到内存
            if (existing is DownloadTask && existing.result.value == null) {
                Timber.d("[TaskPool] EVICT DownloadTask taskId=${existing.taskId}, status=${existing.status.value}, url=$shortUrl")
                val newTask = LoadTask(namedUrl, scope, autoStart)
                _taskMap[namedUrl.url] = newTask
                Timber.d("[TaskPool] REPLACE with LoadTask taskId=${newTask.taskId}, autoStart=$autoStart, poolSize=${_taskMap.size}, url=$shortUrl")
                return newTask
            }
            val file = existing.result.value
            val fileInfo = file?.let { "path=${it.absolutePath}, exists=${it.exists()}, size=${it.length()}" } ?: "null"
            Timber.d("[TaskPool] REUSE taskId=${existing.taskId}, status=${existing.status.value}, file=[$fileInfo], poolSize=${_taskMap.size}, url=$shortUrl")
            return existing
        }
        val newTask = LoadTask(namedUrl, scope, autoStart)
        _taskMap[namedUrl.url] = newTask
        Timber.d("[TaskPool] CREATE taskId=${newTask.taskId}, autoStart=$autoStart, poolSize=${_taskMap.size}, url=$shortUrl")
        return newTask
    }

    fun removeTask(url: String) {
        val shortUrl = url.substringAfterLast('/')
        val removed = _taskMap.remove(url)
        Timber.d("[TaskPool] REMOVE taskId=${removed?.taskId}, status=${removed?.status?.value}, found=${removed != null}, poolSize=${_taskMap.size}, url=$shortUrl")
    }

    @JvmStatic
    fun peekCachedFile(url: String): File? {
        val task = _taskMap[url]
        if (task == null) {
            Timber.d("[DL-CACHE] peekCachedFile no LoadTask for url=$url (pool size=${_taskMap.size})")
            return null
        }
        val file = task.result.value
        if (file == null) {
            Timber.d("[DL-CACHE] peekCachedFile LoadTask found but result is null, status=${task.status.value} url=$url")
            return null
        }
        val exists = file.exists()
        val size = if (exists) file.length() else -1
        if (!exists || size <= 0) {
            Timber.d("[DL-CACHE] peekCachedFile file stale path=${file.absolutePath} exists=$exists size=$size url=$url")
            return null
        }
        Timber.d("[DL-CACHE] peekCachedFile OK path=${file.absolutePath} size=$size url=$url")
        return file
    }

    fun getDownloadTask(namedUrl: NamedUrl): DownloadTask {
        return (_taskMap[namedUrl.url] as? DownloadTask)
            ?: DownloadTask(namedUrl, scope).also {
                _taskMap[namedUrl.url] = it
            }
    }
}