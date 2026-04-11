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
        val existing = _taskMap[namedUrl.url]
        if (existing != null) {
            Timber.d("TaskPool 复用已有任务: taskId=${existing.taskId}, status=${existing.status.value}, url=${namedUrl.url}")
            return existing
        }
        val newTask = LoadTask(namedUrl, scope, autoStart)
        _taskMap[namedUrl.url] = newTask
        Timber.d("TaskPool 创建新任务: taskId=${newTask.taskId}, url=${namedUrl.url}")
        return newTask
    }

    fun removeTask(url: String) {
        _taskMap.remove(url)
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