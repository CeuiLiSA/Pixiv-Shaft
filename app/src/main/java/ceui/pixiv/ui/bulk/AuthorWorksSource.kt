package ceui.pixiv.ui.bulk

import ceui.lisa.http.Retro
import ceui.loxia.Illust

/**
 * 某作者的全部作品（插画或漫画，由 [type] 决定）。
 * type 直接用 [ceui.pixiv.db.queue.WorkType] 的常量，避免再来一份字符串约定。
 */
class AuthorWorksSource(
    private val userId: Long,
    private val type: String,
) : PaginatedObjectSource<Illust> {

    override val sourceTag: String = "user:$userId"
    override val subtitle: String = "user:$userId"
    override val endpointHint: String = "/v1/user/illusts?type=$type"

    override suspend fun firstPage(): PageResult<Illust>? =
        Retro.getAppApiSuspend()
            .getUserSubmitIllust(userId.toInt(), type)
            .toPageResult()

    override suspend fun nextPage(nextUrl: String): PageResult<Illust>? =
        Retro.getAppApiSuspend()
            .getNextIllust(nextUrl)
            .toPageResult()
}
