package ceui.pixiv.ui.novel.reader

import android.util.LruCache
import ceui.loxia.WebNovel
import ceui.pixiv.ui.novel.reader.model.ContentToken

/**
 * 进程内缓存：NovelTextFragment 和 NovelReaderV3Fragment 共用同一套 webNovel +
 * tokens。NovelTextViewModel 在详情页后台预热，NovelReaderV3ViewModel 加载时
 * 先查缓存，命中就跳过网络 + 解析，miss 再正常拉。
 *
 * 简单 LRU 容量 4：同时在内存里保留最多 4 篇。
 */
object NovelTextCache {

    data class Entry(val webNovel: WebNovel, val tokens: List<ContentToken>)

    private val cache = LruCache<Long, Entry>(4)

    fun get(novelId: Long): Entry? = cache.get(novelId)

    fun put(novelId: Long, entry: Entry) {
        cache.put(novelId, entry)
    }

    fun clear() {
        cache.evictAll()
    }
}
