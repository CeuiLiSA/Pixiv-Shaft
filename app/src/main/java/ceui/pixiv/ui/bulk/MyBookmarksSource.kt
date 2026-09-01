package ceui.pixiv.ui.bulk

import ceui.lisa.http.Retro
import ceui.pixiv.api.model.Illust

/**
 * 自己（或任意指定用户）的收藏插画/漫画列表。restrict 一般是 "public" / "private"。
 *
 * 与 [AuthorWorksSource] 区别：
 *   - API 入口走 v1/user/bookmarks/illust（非 user/illusts）
 *   - 这里返回的 list 里 illust/manga 是混着的，所以下游 [bulkEnqueueIllusts] 必须按
 *     `illust.type` 而不是构造级 type 决定 WorkType（已在 enqueueIllustPage 里处理）
 *   - sourceTag 用 `bookmarks:$restrict:$userId` 区分，避免和 user:xxx 撞车
 */
class MyBookmarksSource(
    private val userId: Long,
    private val restrict: String,
    private val tag: String? = null,
) : PaginatedObjectSource<Illust> {

    override val sourceTag: String = buildString {
        append("bookmarks:").append(restrict).append(":").append(userId)
        if (!tag.isNullOrEmpty()) append(":").append(tag)
    }

    override val subtitle: String = buildString {
        append("bookmarks · ").append(restrict)
        if (!tag.isNullOrEmpty()) append(" · #").append(tag)
    }

    override val endpointHint: String =
        "/v1/user/bookmarks/illust?restrict=$restrict" + (tag?.let { "&tag=$it" } ?: "")

    override suspend fun firstPage(): PageResult<Illust>? =
        Retro.getAppApi()
            .getUserLikeIllust(userId, restrict, tag?.takeIf { it.isNotEmpty() })
            .toPageResult()

    override suspend fun nextPage(nextUrl: String): PageResult<Illust>? =
        Retro.getAppApi()
            .getNextIllust(nextUrl)
            .toPageResult()
}
