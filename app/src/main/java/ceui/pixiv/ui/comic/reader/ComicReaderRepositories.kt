package ceui.pixiv.ui.comic.reader

import androidx.lifecycle.LiveData
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.ComicBookmarkDao
import ceui.lisa.database.ComicBookmarkEntity
import ceui.lisa.database.ComicReadingStatsDao
import ceui.lisa.database.ComicReadingStatsEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Repository 层：把 DAO 与协程作用域细节封死在里面，调用方只看到"add / delete / observe / flush"
 * 这种业务语义。所有写入都走 [ComicReaderScope.io] + NonCancellable，跨 Fragment 生命周期可靠落库。
 */
class ComicBookmarkRepository(private val dao: ComicBookmarkDao) {

    fun observeFor(illustId: Long): LiveData<List<ComicBookmarkEntity>> = dao.observeForIllust(illustId)

    fun add(entry: ComicBookmarkEntity) {
        ComicReaderScope.io.launch {
            withContext(NonCancellable) { dao.insert(entry) }
        }
    }

    fun delete(id: Long) {
        ComicReaderScope.io.launch {
            withContext(NonCancellable) { dao.deleteById(id) }
        }
    }

    fun clear(illustId: Long) {
        ComicReaderScope.io.launch {
            withContext(NonCancellable) { dao.clearForIllust(illustId) }
        }
    }

    companion object {
        fun fromContext(): ComicBookmarkRepository =
            ComicBookmarkRepository(AppDatabase.getAppDatabase(Shaft.getContext()).comicBookmarkDao())
    }
}

/** 阅读统计 Repository。同样把 IO + NonCancellable 细节封住。 */
class ComicStatsRepository(private val dao: ComicReadingStatsDao) {

    /** 把一次会话的累计写入对应 illust 的统计行，读改写一气呵成。 */
    fun flushSession(
        illustId: Long,
        lastIndex: Int,
        totalPages: Int,
        durationMs: Long,
        flips: Int,
        completed: Boolean,
    ) {
        if (durationMs <= 0L && flips <= 0) return
        ComicReaderScope.io.launch {
            withContext(NonCancellable) {
                val now = System.currentTimeMillis()
                val existing = dao.getByIllust(illustId) ?: ComicReadingStatsEntity().apply {
                    this.illustId = illustId
                    this.firstReadTime = now
                }
                existing.lastPageIndex = lastIndex
                existing.totalPageCount = if (totalPages > 0) totalPages else existing.totalPageCount
                existing.lastReadTime = now
                existing.totalDurationMs += durationMs
                existing.totalFlips += flips
                existing.openCount = (existing.openCount + 1).coerceAtLeast(1)
                if (completed) existing.completed = 1
                dao.upsert(existing)
            }
        }
    }

    companion object {
        fun fromContext(): ComicStatsRepository =
            ComicStatsRepository(AppDatabase.getAppDatabase(Shaft.getContext()).comicReadingStatsDao())
    }
}
