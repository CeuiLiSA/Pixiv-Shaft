package ceui.pixiv.download.model

import java.time.Instant

data class ItemMeta(
    val id: Long,
    val title: String,
    val author: Author,
    val createdAt: Instant,
    val page: Int? = null,
    val totalPages: Int = 1,
    val width: Int? = null,
    val height: Int? = null,
    val flags: Set<Flag> = emptySet(),
    /** 所属系列标题；不在系列中（或来源拿不到）为 null。 */
    val seriesTitle: String? = null,
    /** 本篇在系列可见列表中的 1-based 位置；批量下载取列表下标，单篇导出查 SeriesCache。 */
    val seriesOrder: Int? = null,
    /**
     * 系列篇数：单篇下载时与 [seriesOrder] 同源，是所在系列的总篇数，用于
     * {series_order} 自动补零的宽度；合并下载（[Bucket.NovelSeries]）时是这一份
     * 合集里实际写进去的章节数。两种场合都由 `{chapters}` 变量读出。
     */
    val seriesTotal: Int? = null,
) {
    fun has(flag: Flag): Boolean = flag in flags
    val isMultiPage: Boolean get() = totalPages > 1
}
