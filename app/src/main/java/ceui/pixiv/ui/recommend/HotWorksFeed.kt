package ceui.pixiv.ui.recommend

import ceui.lisa.activities.Shaft
import ceui.loxia.Illust
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.NovelFeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** shaft-api-v2 两条热度榜口径：TRENDING=本月收藏(当前周收藏加权榜)/RECENT=当前最热(实时流·日周月榜)。 */
enum class HotWorksSource {
    TRENDING,
    RECENT;

    companion object {
        /**
         * 按名字解析，认不出就退回 [TRENDING]。
         *
         * 别用 `valueOf`：它对认不出的名字抛 IllegalArgumentException，而这个值来自 arguments
         * Bundle——系统重建时恢复出来的东西不该有能力把页面打崩。取不准就退回默认 tab，
         * 比崩一次好。
         */
        fun ofName(name: String?): HotWorksSource =
            entries.firstOrNull { it.name == name } ?: TRENDING
    }
}

/** 热度值：trending 取加权 score，recent 取窗口内 bookmark_count（recent 的 score 恒 0）。 */
private fun ShaftApiV2.TrendingWorkItem.hotScore(source: HotWorksSource): Float =
    if (source == HotWorksSource.TRENDING) score.toFloat() else bookmark_count.toFloat()

/** 一页原始 items + next_url。TrendingWorksResponse / RecentWorksResponse 无公共父类，先各自取出再统一。 */
private typealias HotPage = Pair<List<ShaftApiV2.TrendingWorkItem>, String?>

/**
 * 拉一页 shaft-api-v2 热度榜（trending 或 recent，type=illust|manga|novel）：首屏调
 * trending/recentWorks，翻页调 …ByUrl(next_url)。两个响应类型没有公共父类，所以在各 when
 * 分支里各自取 items/next_url（在具体类型上解析），再统一成 [HotPage]。
 */
private suspend fun fetchHotPage(source: HotWorksSource, type: String, window: String?, cursor: String?): HotPage {
    val service = ShaftApiV2Client.service
    return when (source) {
        HotWorksSource.TRENDING -> {
            val resp = if (cursor == null) service.trendingWorks(type = type)
            else service.trendingWorksByUrl(cursor)
            resp.items to resp.next_url
        }
        HotWorksSource.RECENT -> {
            val resp = if (cursor == null) service.recentWorks(type = type, window = window)
            else service.recentWorksByUrl(cursor)
            resp.items to resp.next_url
        }
    }
}

/**
 * 「本月收藏」/「当前最热」的插画·漫画数据源（feeds 框架版，替代 legacy TrendingWorksRepo /
 * RecentWorksRepo）。
 *
 * shaft-api-v2 的响应不实现 KListShow（item.bean 是 JsonObject），用不了 PixivFeedSource，
 * 这里直接写 [FeedSource]。逐条 item.bean → Illust，装 trendingScore、清 is_bookmarked
 * （payload 里是上报者的收藏态，跟当前用户无关），再走 [IllustFeedItem.of]（含全局内容
 * 过滤 + bean→loxia Illust）。零 Fragment 捕获（source/type/window 都是构造进来的局部值）。
 */
class HotWorksIllustFeedSource(
    private val source: HotWorksSource,
    /** 服务端 enum："illust" | "manga"。 */
    private val type: String,
    /** 仅 recent 用：null=实时流，day|week|month=实时日/周/月榜；trending 固定 week，忽略此值。 */
    private val window: String?,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val (items, nextUrl) = fetchHotPage(source, type, window, cursor)
        // gson 解析 + 过滤挪到 Default，保住 load 的 main-safe 契约
        val feedItems = withContext(Dispatchers.Default) {
            items.mapNotNull { mapHotIllustItem(it, source) }
        }
        return FeedPage(feedItems, nextUrl?.takeIf { it.isNotEmpty() })
    }
}

/** 小说版（type=novel）。同上，只是 item.bean → loxia Novel，热度分单独带进 NovelFeedItem。 */
class HotWorksNovelFeedSource(
    private val source: HotWorksSource,
    private val window: String?,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val (items, nextUrl) = fetchHotPage(source, "novel", window, cursor)
        val feedItems = withContext(Dispatchers.Default) {
            items.mapNotNull { mapHotNovelItem(it, source) }
        }
        return FeedPage(feedItems, nextUrl?.takeIf { it.isNotEmpty() })
    }
}

// ── item.bean → FeedItem（跑在 Default 线程，纯函数，零捕获） ──

private fun mapHotIllustItem(
    item: ShaftApiV2.TrendingWorkItem,
    source: HotWorksSource,
): IllustFeedItem? {
    val json = item.bean ?: return null
    val bean = try {
        Shaft.sGson.fromJson(json, Illust::class.java)
    } catch (e: Throwable) {
        Timber.tag("HotWorks").w(e, "skip malformed illust bean id=${item.target_id}")
        return null
    } ?: return null
    // 对齐 legacy TrendingWorksRepo/RecentWorksRepo：热度值装 pill、清上报者收藏态。
    return IllustFeedItem.of(bean.withTrendingScore(item.hotScore(source)).withBookmarked(false))
}

private fun mapHotNovelItem(
    item: ShaftApiV2.TrendingWorkItem,
    source: HotWorksSource,
): NovelFeedItem? {
    val json = item.bean ?: return null
    val novel = try {
        Shaft.sGson.fromJson(json, Novel::class.java)
    } catch (e: Throwable) {
        Timber.tag("HotWorks").w(e, "skip malformed novel bean id=${item.target_id}")
        return null
    } ?: return null
    // 清上报者收藏态；热度分单独带进 NovelFeedItem（不是 Novel 的字段）。
    return NovelFeedItem.of(novel.copy(is_bookmarked = false), item.hotScore(source))
}
