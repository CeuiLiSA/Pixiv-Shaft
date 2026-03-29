package ceui.pixiv.ui.task

import kotlinx.coroutines.CoroutineScope
import timber.log.Timber

object TaskPool {

    private const val MAX_CACHE_SIZE = 256

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

    fun getLoadTask(namedUrl: NamedUrl, coroutineScope: CoroutineScope, autoStart: Boolean = true): LoadTask {
        val existing = _taskMap[namedUrl.url]
        if (existing != null) {
            Timber.d("TaskPool 复用已有任务: taskId=${existing.taskId}, status=${existing.status.value}, url=${namedUrl.url}")
            return existing
        }
        val newTask = LoadTask(namedUrl, coroutineScope, autoStart)
        _taskMap[namedUrl.url] = newTask
        Timber.d("TaskPool 创建新任务: taskId=${newTask.taskId}, url=${namedUrl.url}")
        return newTask
    }

    fun removeTask(url: String) {
        _taskMap.remove(url)
    }

    fun getDownloadTask(namedUrl: NamedUrl, coroutineScope: CoroutineScope): DownloadTask {
        return (_taskMap[namedUrl.url] as? DownloadTask)
            ?: DownloadTask(namedUrl, coroutineScope).also {
                _taskMap[namedUrl.url] = it
            }
    }
}