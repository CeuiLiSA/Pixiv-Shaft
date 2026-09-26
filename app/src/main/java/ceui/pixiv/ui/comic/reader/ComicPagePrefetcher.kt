package ceui.pixiv.ui.comic.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 真正给 [ComicReaderSettings.preloadAhead] 兜底的主动预取器。
 * 设置值变化时，下一次 [prefetchAround] 会立即按新值生效，不像 ViewPager2.offscreenPageLimit
 * 那样只在 view 创建时读一次。
 *
 * 对同一 (current, end) 二元组做 dedup，避免每次翻页都重复预取。
 *
 * 预取动作由调用方注入的 [prefetch] 完成：它内部走统一的
 * [ceui.pixiv.imageloader.PageImageSourceResolver] —— 本地已下载 / 本地 URL / 已缓存时什么都不做
 * （本来就不需要下），需要网络时 resolver 内部已经 `obtain` 过、下载就排上队了。本类因此不必
 * （也不该）自己认识 ImageLoaderV3。
 */
class ComicPagePrefetcher(private val scope: CoroutineScope) {
    private var lastFingerprint: Long = -1L

    fun prefetchAround(
        pages: List<ComicReaderV3ViewModel.ComicPage>,
        currentIndex: Int,
        prefetch: suspend (ComicReaderV3ViewModel.ComicPage, String) -> Unit,
    ) {
        if (pages.isEmpty()) return
        val ahead = ComicReaderSettings.preloadAhead
        if (ahead <= 0) return
        val end = (currentIndex + ahead).coerceAtMost(pages.size - 1)
        val original = ComicReaderSettings.loadOriginal
        // 把 (current, end, original) 编码成 long 当作指纹，三者任一变化都会触发新一轮预取。
        val fp = (currentIndex.toLong() shl 33) or (end.toLong() shl 1) or (if (original) 1L else 0L)
        if (fp == lastFingerprint) return
        lastFingerprint = fp
        scope.launch {
            for (i in (currentIndex + 1)..end) {
                val page = pages.getOrNull(i) ?: break
                prefetch(page, if (original) page.originalUrl else page.previewUrl)
            }
        }
    }

    fun reset() { lastFingerprint = -1L }
}
