package ceui.pixiv.ui.common

import ceui.lisa.activities.Shaft
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.services.appServices

/**
 * 自建源列表（shaft-api-v2 榜单 / 热度 / 壁纸、pixiv 网页 ajax、发现池快照）的收藏态回填。
 *
 * 这些 payload 里的 `is_bookmarked` 是上报者 / 匿名网页的视角，source 一律清成 false，
 * 于是卡片上永远是灰心 —— 明明收藏了也看不出来。pixiv 没有按 id 批量查收藏态的接口，
 * 逐条打 detail 又太贵，所以这里问本地收藏镜像：**只升不降**，镜像里有就是 true，
 * 没有就保持原样（镜像没回填完 ≠ 没收藏）。
 *
 * - 每页一次 Room 查询，只查当前还是「未收藏」的 id，走 targetId 索引；
 * - **不写池**：这些 bean 的来源本来就不可信，写进池只会污染（见 [ceui.pixiv.cache.ObjectPool]
 *   的「viewer 态可信度」）。详情页会自己按镜像校正，不靠这里带过去的值；
 * - 只翻布尔，不动 `total_bookmarks`：payload 里的收藏数本来就包含当前用户这一份，
 *   加减会让数字对不上。
 */
internal suspend fun List<FeedItem>.withMirroredBookmarks(): List<FeedItem> {
    val illustIds = mapNotNull { item ->
        (item as? IllustFeedItem)?.illust?.takeIf { it.is_bookmarked != true }?.id
    }.distinct()
    val novelIds = mapNotNull { item ->
        (item as? NovelFeedItem)?.novel?.takeIf { it.is_bookmarked != true }?.id
    }.distinct()
    if (illustIds.isEmpty() && novelIds.isEmpty()) return this
    val mirror = Shaft.getContext().appServices().bookmarkMirror
    val bookmarkedIllusts =
        if (illustIds.isEmpty()) emptySet()
        else mirror.bookmarkedAmong(MirrorContentType.ILLUST, illustIds)
    val bookmarkedNovels =
        if (novelIds.isEmpty()) emptySet()
        else mirror.bookmarkedAmong(MirrorContentType.NOVEL, novelIds)
    if (bookmarkedIllusts.isEmpty() && bookmarkedNovels.isEmpty()) return this
    return map { item ->
        when {
            item is IllustFeedItem && item.illust.is_bookmarked != true &&
                item.illust.id in bookmarkedIllusts ->
                IllustFeedItem(item.illust.withBookmarked(true))

            item is NovelFeedItem && item.novel.is_bookmarked != true &&
                item.novel.id in bookmarkedNovels ->
                NovelFeedItem(item.novel.copy(is_bookmarked = true), item.trendingScore)

            else -> item
        }
    }
}