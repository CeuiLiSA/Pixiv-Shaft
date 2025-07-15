package ceui.pixiv.ui.task

import kotlinx.coroutines.CoroutineScope

object TaskPool {

    private const val MAX_CACHE_SIZE = 256

    // 单独的 LoadTask 缓存
    private val loadTaskMap = object : LinkedHashMap<String, LoadTask>(
        MAX_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, LoadTask>): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    // 单独的 DownloadTask 缓存
    private val downloadTaskMap = object : LinkedHashMap<String, DownloadTask>(
        MAX_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, DownloadTask>): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    fun getLoadTask(
        namedUrl: NamedUrl,
        coroutineScope: CoroutineScope,
        autoStart: Boolean = true
    ): LoadTask {
        return synchronized(loadTaskMap) {
            loadTaskMap.getOrPut(namedUrl.url) {
                LoadTask(namedUrl, coroutineScope, autoStart)
            }
        }
    }

    fun getDownloadTask(namedUrl: NamedUrl, coroutineScope: CoroutineScope): DownloadTask {
        return synchronized(downloadTaskMap) {
            downloadTaskMap.getOrPut(namedUrl.url) {
                DownloadTask(namedUrl, coroutineScope)
            }
        }
    }
}
