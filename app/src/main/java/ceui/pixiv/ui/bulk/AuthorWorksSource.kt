package ceui.pixiv.ui.bulk

import ceui.pixiv.api.Client
import ceui.pixiv.api.model.Illust

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
        Client.appApi
            .getUserSubmitIllust(userId, type)
            .toPageResult()

    override suspend fun nextPage(nextUrl: String): PageResult<Illust>? =
        Client.appApi
            .getNextIllust(nextUrl)
            .toPageResult()
}
