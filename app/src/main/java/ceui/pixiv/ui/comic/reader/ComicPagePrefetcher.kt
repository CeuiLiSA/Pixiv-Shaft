package ceui.pixiv.ui.comic.reader

import ceui.pixiv.ui.task.NamedUrl
import ceui.pixiv.ui.task.TaskPool

/**
 * 真正给 [ComicReaderSettings.preloadAhead] 兜底的主动预取器。
 * 设置值变化时，下一次 [prefetchAround] 会立即按新值生效，不像 ViewPager2.offscreenPageLimit
 * 那样只在 view 创建时读一次。
 *
 * 对同一 (current, end) 二元组做 dedup，避免每次翻页都重复 hit TaskPool。
 */
class ComicPagePrefetcher {
    private var lastFingerprint: Long = -1L

    fun prefetchAround(pages: List<ComicReaderV3ViewModel.ComicPage>, currentIndex: Int) {
        if (pages.isEmpty()) return
        val ahead = ComicReaderSettings.preloadAhead
        if (ahead <= 0) return
        val end = (currentIndex + ahead).coerceAtMost(pages.size - 1)
        val original = ComicReaderSettings.loadOriginal
        // 把 (current, end, original) 编码成 long 当作指纹，三者任一变化都会触发新一轮预取。
        val fp = (currentIndex.toLong() shl 33) or (end.toLong() shl 1) or (if (original) 1L else 0L)
        if (fp == lastFingerprint) return
        lastFingerprint = fp
        for (i in (currentIndex + 1)..end) {
            val url = if (original) pages[i].originalUrl else pages[i].previewUrl
            TaskPool.getLoadTask(NamedUrl("", url), autoStart = true)
        }
    }

    fun reset() { lastFingerprint = -1L }
}
