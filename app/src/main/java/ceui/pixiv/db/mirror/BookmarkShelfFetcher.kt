package ceui.pixiv.db.mirror

import ceui.pixiv.api.Client
import ceui.pixiv.api.model.IllustResponse
import ceui.pixiv.api.model.NovelResponse
import ceui.pixiv.api.model.UserPreviewResponse
import ceui.pixiv.feeds.pixiv.replayNextUrl
import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 网络回来的一件作品：先只认 id（引擎要拿它去查「库里已有的序号」），
 * 等序号定了再 [toRow] 建行。
 *
 * 做成延迟建行而不是直接给 `BookmarkMirrorEntity`，是因为 `bookmarkSeq` 只有引擎
 * 知道（要综合库里已有的值和本轮号段），而 gson 序列化 + 标签展开又不该在引擎里重写一遍。
 */
class MirrorItem(
    val id: Long,
    private val build: (seq: Long, generation: Int, now: Long) -> MirrorRow,
) {
    fun toRow(seq: Long, generation: Int, now: Long): MirrorRow = build(seq, generation, now)
}

/** 一页网络结果。[nextUrl] 为空 = 这个书架翻到底了。 */
class FetchedPage(val items: List<MirrorItem>, val nextUrl: String?)

/**
 * 一个书架的翻页协议。插画收藏、小说收藏、关注各一个实现，公开/悄悄只是 `restrict` 参数的差别。
 *
 * 新增可镜像的列表（例如「按收藏标签筛出来的子集」）= 加一个实现，引擎一行不用改。
 */
interface BookmarkShelfFetcher {
    val shelf: BookmarkShelf

    /** [nextUrl] 为 null 拉第一页，否则续翻。抛出的异常由引擎分类处理。 */
    suspend fun load(nextUrl: String?): FetchedPage
}

/**
 * 翻页反序列化用的 Gson。与 `PixivFeedSource` 同一个理由：无自定义适配器的 vanilla Gson，
 * 线程安全、内部按类型缓存 TypeAdapter，整个镜像系统共用一个就够。
 */
private val pagingGson = Gson()

/** 插画/漫画收藏（`/v1/user/bookmarks/illust`）。 */
class IllustBookmarkFetcher(override val shelf: BookmarkShelf) : BookmarkShelfFetcher {

    override suspend fun load(nextUrl: String?): FetchedPage {
        val response = if (nextUrl == null) {
            Client.appApi.getUserBookmarkedIllusts(shelf.ownerUid, shelf.restrict.apiValue, null)
        } else {
            replayNextUrl(pagingGson, nextUrl, IllustResponse::class.java)
        }
        return FetchedPage(
            items = response.illusts.map { illust ->
                MirrorItem(illust.id) { seq, generation, now ->
                    BookmarkMirrorMapper.fromIllust(shelf, illust, seq, generation, now)
                }
            },
            nextUrl = response.next_url?.takeIf { it.isNotEmpty() },
        )
    }
}

/** 小说收藏（`/v1/user/bookmarks/novel`）。 */
class NovelBookmarkFetcher(override val shelf: BookmarkShelf) : BookmarkShelfFetcher {

    override suspend fun load(nextUrl: String?): FetchedPage {
        val response = if (nextUrl == null) {
            Client.appApi.getUserBookmarkedNovels(shelf.ownerUid, shelf.restrict.apiValue, null)
        } else {
            replayNextUrl(pagingGson, nextUrl, NovelResponse::class.java)
        }
        return FetchedPage(
            items = response.novels.map { novel ->
                MirrorItem(novel.id) { seq, generation, now ->
                    BookmarkMirrorMapper.fromNovel(shelf, novel, seq, generation, now)
                }
            },
            nextUrl = response.next_url?.takeIf { it.isNotEmpty() },
        )
    }
}

/**
 * 关注的用户（`/v1/user/following`）。
 *
 * 与收藏接口的区别只有一处：它的 next_url 是 **offset** 游标而不是 `max_bookmark_id`，
 * 对翻页途中的增删不稳定：
 * - 途中新关注一位 → 整体后移一格，下一页头一条是上一页见过的，按 id 沿用旧序号、无害；
 * - 途中取关一位 → 整体前移一格，下一页会**跳过一位仍在关注的人**。回填时他就缺席到下次重扫；
 *   重扫时更糟 —— 这一轮没见过他，收尾的 `deleteStaleRows` 会把一个仍在关注的人删掉。
 *   而「一边在关注库里取关、一边后台在扫」恰恰是最自然的操作。
 *
 * 所以每页的续传 offset 回退 [PAGE_OVERLAP] 格，让相邻两页重叠（见 [overlapNextUrl]）：
 * 两页之间最多取关 [PAGE_OVERLAP] 位都不会漏人。重叠的人是已知 id，写入幂等；
 * 限速看的是请求间隔，重叠只让全量多翻几页，不提高请求频率。
 */
class FollowingUserFetcher(override val shelf: BookmarkShelf) : BookmarkShelfFetcher {

    override suspend fun load(nextUrl: String?): FetchedPage {
        val response = if (nextUrl == null) {
            Client.appApi.getFollowingUsers(shelf.ownerUid, shelf.restrict.apiValue)
        } else {
            replayNextUrl(pagingGson, nextUrl, UserPreviewResponse::class.java)
        }
        return FetchedPage(
            // 没有 user 的预览没有身份（也渲染不出卡片），同 toUserFeedItems 的口径丢掉
            items = response.user_previews.mapNotNull { preview ->
                val id = preview.user?.id?.takeIf { it > 0L } ?: return@mapNotNull null
                MirrorItem(id) { seq, generation, now ->
                    BookmarkMirrorMapper.fromUserPreview(shelf, preview, seq, generation, now)
                }
            },
            nextUrl = overlapNextUrl(nextUrl, response.next_url?.takeIf { it.isNotEmpty() }, PAGE_OVERLAP),
        )
    }

    private companion object {
        /** 一页 30 位，回退 5 位：全量多翻约 1/5 的页数，换两页之间容得下 5 次取关。 */
        const val PAGE_OVERLAP = 5
    }
}

/**
 * 把 offset 型 next_url 的 `offset` 回退 [overlap] 格，但**绝不回退到当前页或更早**
 * （否则引擎会原地打转）。不是 offset 型的 URL、解析不了的 URL 原样返回。
 *
 * @param requested 本页请求用的 URL（null = 第一页，offset 视为 0）
 */
internal fun overlapNextUrl(requested: String?, next: String?, overlap: Int): String? {
    val nextUrl = next?.toHttpUrlOrNull() ?: return next
    val nextOffset = nextUrl.queryParameter("offset")?.toIntOrNull() ?: return next
    val current = requested?.toHttpUrlOrNull()?.queryParameter("offset")?.toIntOrNull() ?: 0
    val rewound = maxOf(nextOffset - overlap, current + 1)
    if (rewound >= nextOffset) return next
    return nextUrl.newBuilder().setQueryParameter("offset", rewound.toString()).build().toString()
}

/** 按书架类型选实现。 */
fun fetcherFor(shelf: BookmarkShelf): BookmarkShelfFetcher = when (shelf.contentType) {
    MirrorContentType.ILLUST -> IllustBookmarkFetcher(shelf)
    MirrorContentType.NOVEL -> NovelBookmarkFetcher(shelf)
    MirrorContentType.USER -> FollowingUserFetcher(shelf)
}
