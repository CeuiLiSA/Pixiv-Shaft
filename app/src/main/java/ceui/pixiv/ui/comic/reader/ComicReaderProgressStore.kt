package ceui.pixiv.ui.comic.reader

import com.tencent.mmkv.MMKV

/** 每个 illust 的阅读进度（最后一次的页面索引 + 时间戳）。 */
object ComicReaderProgressStore {

    private const val MMKV_ID = "comic_reader_v3_progress"
    private val store: MMKV by lazy { MMKV.mmkvWithID(MMKV_ID) }

    fun savePage(illustId: Long, pageIndex: Int, totalPages: Int) {
        store.encode("page_$illustId", pageIndex)
        store.encode("total_$illustId", totalPages)
        store.encode("time_$illustId", System.currentTimeMillis())
    }

    fun lastPage(illustId: Long): Int = store.decodeInt("page_$illustId", 0)

    fun lastReadTime(illustId: Long): Long = store.decodeLong("time_$illustId", 0L)

    fun clear(illustId: Long) {
        store.removeValueForKey("page_$illustId")
        store.removeValueForKey("total_$illustId")
        store.removeValueForKey("time_$illustId")
    }
}
