package ceui.pixiv.ui.novel.reader

import com.tencent.mmkv.MMKV

/**
 * Lightweight MMKV-backed per-novel progress storage. Serves as the bridge layer
 * until the full Room-backed reading stats are wired up; callers get the same
 * API so switching later is a one-line change.
 */
object ReaderProgressStore {

    private const val MMKV_ID = "novel_reader_v3_progress"

    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    fun saveProgress(novelId: Long, charIndex: Int, pageIndex: Int, totalPages: Int) {
        store.encode("char_$novelId", charIndex)
        store.encode("page_$novelId", pageIndex)
        store.encode("total_$novelId", totalPages)
        store.encode("time_$novelId", System.currentTimeMillis())
    }

    fun loadCharIndex(novelId: Long): Int = store.decodeInt("char_$novelId", 0)

    fun loadLastPageIndex(novelId: Long): Int = store.decodeInt("page_$novelId", 0)

    /** 保存那一刻的总页数；滚动模式存的是 0（它没有页的概念）。 */
    fun loadTotalPages(novelId: Long): Int = store.decodeInt("total_$novelId", 0)

    fun loadLastReadTime(novelId: Long): Long = store.decodeLong("time_$novelId", 0L)

    /**
     * 系列里最后在阅读器打开的那一篇：系列页的「继续阅读 · 第 N 篇」靠它。只按单篇进度推不出来 ——
     * 超长系列的后几页章节系列页还没翻到，看不到它们的进度。
     */
    fun saveSeriesLastNovel(seriesId: Long, novelId: Long) {
        store.encode("series_last_$seriesId", novelId)
    }

    /** 没记过返回 0。 */
    fun loadSeriesLastNovel(seriesId: Long): Long = store.decodeLong("series_last_$seriesId", 0L)

    fun clear(novelId: Long) {
        store.removeValueForKey("char_$novelId")
        store.removeValueForKey("page_$novelId")
        store.removeValueForKey("total_$novelId")
        store.removeValueForKey("time_$novelId")
    }
}
