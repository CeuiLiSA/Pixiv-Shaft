package ceui.pixiv.ui.task

import androidx.fragment.app.FragmentActivity

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

    fun getLoadTask(namedUrl: NamedUrl, activity: FragmentActivity, autoStart: Boolean = true): LoadTask {
        return _taskMap.getOrPut(namedUrl.url) {
            LoadTask(namedUrl, activity, autoStart)
        }
    }

    fun getDownloadTask(namedUrl: NamedUrl, activity: FragmentActivity): DownloadTask {
        return (_taskMap[namedUrl.url] as? DownloadTask)
            ?: DownloadTask(namedUrl, activity).also {
                _taskMap[namedUrl.url] = it
            }
    }
}