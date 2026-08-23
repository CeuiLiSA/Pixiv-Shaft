package ceui.pixiv.ui.bulk

/**
 * 一页结果：当 nextUrl 为 null/空 时表示走到末尾。
 *
 * 这是 [BulkObjectFetcher] 抽象的核心约束：source 必须能告诉 fetcher "还有没有下一页"。
 * 推荐流（首页）这种没结尾的列表不应实现该接口。
 */
data class PageResult<T>(val items: List<T>, val nextUrl: String?)

/**
 * 通用的"可翻页且有末尾"对象源。T 不限于 Illust —— 后续 list-user / list-novel
 * 的批量场景也走同一接口。
 */
interface PaginatedObjectSource<T> {
    /** 写进下游持久化（download_queue.sourceTag 等）用来区分来源，例：`user:12345`、`bookmarks:public`。 */
    val sourceTag: String

    /** 拼进 dialog 标题：`batch · $subtitle`。需对人类可读。 */
    val subtitle: String

    /** 第一次 Networking 事件展示的 endpoint 字符串（已脱敏/精简）。 */
    val endpointHint: String

    suspend fun firstPage(): PageResult<T>?
    suspend fun nextPage(nextUrl: String): PageResult<T>?
}
