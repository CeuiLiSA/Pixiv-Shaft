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
    /** 本篇在系列中的 1-based 序号；只有「从系列批量下载」链路能拿到。 */
    val seriesOrder: Int? = null,
    /** 系列总篇数，与 [seriesOrder] 同源，用于 {series_order} 自动补零的宽度。 */
    val seriesTotal: Int? = null,
) {
    fun has(flag: Flag): Boolean = flag in flags
    val isMultiPage: Boolean get() = totalPages > 1
}
