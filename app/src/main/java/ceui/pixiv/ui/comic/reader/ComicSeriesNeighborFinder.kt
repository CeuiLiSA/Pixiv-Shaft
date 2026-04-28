package ceui.pixiv.ui.comic.reader

import ceui.loxia.Client

/**
 * 抓取整个 illust 系列（最多 10 页 / 300 篇上限），返回当前 illustId 在序列里的相邻篇。
 * 拉取使用与 NovelReaderV3Fragment 相同的接力策略。
 */
object ComicSeriesNeighborFinder {

    suspend fun findNeighbor(
        seriesId: Long,
        currentIllustId: Long,
        forward: Boolean,
        maxPages: Int = 10,
    ): Long? {
        val all = mutableListOf<Long>()
        var lastOrder: Int? = null
        for (page in 0 until maxPages) {
            val resp = runCatching { Client.appApi.getIllustSeries(seriesId, lastOrder) }.getOrNull() ?: break
            resp.illusts?.forEach { all.add(it.id) }
            if (resp.next_url == null) break
            lastOrder = all.size
        }
        val idx = all.indexOf(currentIllustId)
        if (idx < 0) return null
        return if (forward) all.getOrNull(idx + 1) else all.getOrNull(idx - 1)
    }
}
