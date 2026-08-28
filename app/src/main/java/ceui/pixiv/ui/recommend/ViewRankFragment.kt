package ceui.pixiv.ui.recommend

import android.os.Bundle
import androidx.fragment.app.Fragment
import ceui.lisa.R
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.loxia.Illust
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.NovelFeedFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全站浏览量榜宿主:插画 / 漫画 / 小说 三个类型 tab([TypeTabsRankFragment]),打自建
 * shaft-api-v2 的 discover/most-viewed?type=,单作按 pixiv 总浏览数排(含 R-18),热度 pill
 * 显浏览数。插画·漫画 tab 是 [ViewRankIllustFeedFragment],小说 tab 是 [ViewRankNovelFeedFragment]。
 */
class ViewRankFragment : TypeTabsRankFragment() {

    override val titleRes: Int get() = R.string.view_rank_title

    override fun createPage(type: String): Fragment = if (type == RankType.NOVEL) {
        ViewRankNovelFeedFragment()
    } else {
        ViewRankIllustFeedFragment.newInstance(type)
    }

    companion object {
        @JvmStatic
        fun newInstance(): ViewRankFragment = ViewRankFragment()
    }
}

/** 浏览量榜的插画 / 漫画 feed 子页。约定(autoLoad=false / 不喂池 / 不透传 next_url)同 [BookmarkRankIllustFeedFragment]。 */
class ViewRankIllustFeedFragment : IllustFeedFragment() {

    override val feedViewModel by feedViewModels(autoLoad = false) {
        val type = requireArguments().getString(ARG_TYPE) ?: RankType.ILLUST
        ViewRankFeedSource(type = type)
    }

    override val detailContinuationCursor: String? get() = null

    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    companion object {
        private const val ARG_TYPE = "view_rank_type"

        /** [type] 是 [RankType.ILLUST] / [RankType.MANGA](服务端 enum,别本地化)。 */
        @JvmStatic
        fun newInstance(type: String): ViewRankIllustFeedFragment {
            return ViewRankIllustFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, type)
                }
            }
        }
    }
}

/** 浏览量榜的小说 feed 子页:复用 [NovelFeedFragment] 的小说卡,数据 type=novel。 */
class ViewRankNovelFeedFragment : NovelFeedFragment() {

    override val feedViewModel by feedViewModels(autoLoad = false) {
        ViewRankFeedSource(type = RankType.NOVEL)
    }
}

/**
 * 浏览量榜数据源:shaft-api-v2 discover/most-viewed(首屏 mostViewed,翻页 mostViewedByUrl)。
 * 响应不实现 KListShow(item.bean 是 JsonObject),用不了 PixivFeedSource,手写 [FeedSource]。
 * 逐条 item.bean → Illust / Novel(按 [type]),装 trendingScore=浏览数、清 is_bookmarked
 * (payload 里是上报者的收藏态),见 [toRankFeedItem](含全局内容过滤,对齐 legacy 基类 Mapper)。
 * 零 Fragment 捕获(type/limit 是构造进来的局部值,map 是纯函数)。
 */
class ViewRankFeedSource(
    private val type: String = RankType.ILLUST,
    private val limitN: Int = 30,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: ShaftApiV2.MostViewedResponse = if (cursor == null) {
            ShaftApiV2Client.service.mostViewed(type = type, limit = limitN)
        } else {
            ShaftApiV2Client.service.mostViewedByUrl(cursor)
        }
        val type = type
        // gson 解析 + 内容过滤挪 Default,保住 load 的 main-safe 契约。
        // 浏览量榜:pill 显浏览数(TrendingScoreFormat 支持 M,6457227→「6.5M」)。
        val items = withContext(Dispatchers.Default) {
            resp.items.mapNotNull {
                it.toRankFeedItem(type, it.view_count.toFloat(), logTag = "ViewRank")
            }
        }
        return FeedPage(items, resp.next_url?.takeIf { it.isNotEmpty() })
    }
}
