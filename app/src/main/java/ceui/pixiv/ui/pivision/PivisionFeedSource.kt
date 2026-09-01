package ceui.pixiv.ui.pivision

import ceui.pixiv.api.Client
import ceui.pixiv.api.model.ArticlesResponse
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.pixiv.PixivFeedSource
import ceui.pixiv.feeds.pixiv.cachedPixivFeedSource

/** pixivision 分类。发现页货架与特辑页的 illust tab 共用 [CATEGORY_ILLUST]（同一份本地优先缓存）。 */
const val PIVISION_CATEGORY_ILLUST = "illust"
const val PIVISION_CATEGORY_MANGA = "manga"

/**
 * 特辑数据源（本地优先）。**slot 按 category 走**，所以拉同一 category 的页面天然共用同一份磁盘
 * 快照：发现页货架和特辑页 illust tab 都是 `pivision-illust` —— 谁先冷启拉到，另一边下次进来
 * 就秒显，不用各拉一次。
 *
 * 顶层函数 + 无捕获：数据源被 [ceui.pixiv.feeds.FeedViewModel] 持有到页面销毁，不能捕获 Fragment。
 */
fun pivisionSource(category: String): PixivFeedSource<ArticlesResponse> = cachedPixivFeedSource(
    slot = "pivision-$category",
    initialFetch = { Client.appApi.pixivsionArticles(category) },
) { resp, _ -> resp.displayList.map { ArticleFeedItem(it) } }

/**
 * 只取第一页的包装：把 [delegate] 的 nextCursor 抹成 null（到底），其余（网络拉取、首屏落盘、
 * 缓存恢复）全部照走 —— 这样货架能和整页共用同一份缓存 slot，但自己不翻页
 * （对齐 legacy PivisionRepo(isHorizontal=true) 的 initNextApi=null）。
 */
class SinglePageFeedSource(
    private val delegate: PixivFeedSource<ArticlesResponse>,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> =
        delegate.load(cursor).copy(nextCursor = null)

    override suspend fun loadFromCache(): FeedPage<String>? =
        delegate.loadFromCache()?.copy(nextCursor = null)
}
