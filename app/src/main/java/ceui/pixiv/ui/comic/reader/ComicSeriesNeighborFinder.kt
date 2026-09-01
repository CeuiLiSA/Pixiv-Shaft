package ceui.pixiv.ui.comic.reader

import ceui.pixiv.cache.SeriesCache

/**
 * 返回当前 illustId 在系列里的相邻篇。整条系列走 [SeriesCache] 进程内缓存——第一次翻页
 * 拉一次（最多 10 页 / 300 篇上限），之后左右翻页 / 开选话 sheet 全部命中缓存，不再打网络。
 * orderedIds 就是接口原顺序（漫画降序），forward=idx+1 的语义与旧实现完全一致。
 */
object ComicSeriesNeighborFinder {

    suspend fun findNeighbor(
        seriesId: Long,
        currentIllustId: Long,
        forward: Boolean,
        maxPages: Int = 10,
    ): Long? {
        val entry = runCatching { SeriesCache.loadIllustSeries(seriesId, maxPages) }.getOrNull()
            ?: return null
        val items = entry.items
        val idx = items.indexOfFirst { it.id == currentIllustId }
        if (idx < 0) return null
        return if (forward) items.getOrNull(idx + 1)?.id else items.getOrNull(idx - 1)?.id
    }
}
