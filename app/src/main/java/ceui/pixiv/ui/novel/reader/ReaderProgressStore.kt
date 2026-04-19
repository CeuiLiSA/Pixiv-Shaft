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

    fun loadLastReadTime(novelId: Long): Long = store.decodeLong("time_$novelId", 0L)

    fun clear(novelId: Long) {
        store.removeValueForKey("char_$novelId")
        store.removeValueForKey("page_$novelId")
        store.removeValueForKey("total_$novelId")
        store.removeValueForKey("time_$novelId")
    }
}
