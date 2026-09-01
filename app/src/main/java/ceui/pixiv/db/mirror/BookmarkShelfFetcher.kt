package ceui.pixiv.db.mirror

import ceui.pixiv.api.Client
import ceui.pixiv.api.model.IllustResponse
import ceui.pixiv.api.model.NovelResponse
import ceui.pixiv.feeds.pixiv.replayNextUrl
import com.google.gson.Gson

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
 * 一个书架的翻页协议。插画和小说各一个实现，公开/悄悄收藏只是 `restrict` 参数的差别。
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

/** 按书架类型选实现。 */
fun fetcherFor(shelf: BookmarkShelf): BookmarkShelfFetcher = when (shelf.contentType) {
    MirrorContentType.ILLUST -> IllustBookmarkFetcher(shelf)
    MirrorContentType.NOVEL -> NovelBookmarkFetcher(shelf)
}
