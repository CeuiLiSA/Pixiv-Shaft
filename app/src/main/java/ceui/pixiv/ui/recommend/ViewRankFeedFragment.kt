package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.models.IllustsBean
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.FollowStateBackfill
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 全站浏览量榜（feeds 框架版，替代 legacy FragmentViewRank + ViewRankRepo + IAdapter）。
 * 单作按 pixiv 总浏览数排（含 R-18），打自建 shaft-api-v2 的 discover/most-viewed；
 * 普通插画瀑布流 + 自带 toolbar（fragment_toolbar_feed），热度 pill 显浏览数。
 */
class ViewRankFeedFragment : IllustFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels {
        ViewRankFeedSource()
    }

    // shaft-api-v2 的 next_url 是 shaft 绝对 URL，不是 app-api illust nextUrl；别漏进详情页 pager
    // （getNextIllust 拿它当 @Url 请求会拿到 MostViewedResponse 形状，解析成空 IllustResponse）。
    override val detailContinuationCursor: String? get() = null

    // 榜单 bean 是第三方上报快照：is_bookmarked 被 source 伪造成 false、user.is_followed 是
    // 上报者的——都不可信，喂池会把当前用户更新的收藏/关注态盖回去。同 WatchLaterFeedFragment 先例。
    override fun poolableBeansOf(item: FeedItem): List<IllustsBean> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.view_rank_title)
    }

    companion object {
        @JvmStatic
        fun newInstance(): ViewRankFeedFragment = ViewRankFeedFragment()
    }
}

/**
 * 浏览量榜数据源：shaft-api-v2 discover/most-viewed（首屏 mostViewed，翻页 mostViewedByUrl）。
 * 响应不实现 KListShow（item.bean 是 JsonObject），用不了 PixivFeedSource，手写 [FeedSource]。
 * 逐条 item.bean → IllustsBean，装 trendingScore=浏览数、清 is_bookmarked（payload 里是上报者
 * 的收藏态），再走 [IllustFeedItem.fromBean]（含全局内容过滤，对齐 legacy 基类 Mapper）。
 * 零 Fragment 捕获（type/limit 是构造进来的局部值，map 是伴生纯函数）。
 */
class ViewRankFeedSource(
    private val type: String = "illust",
    private val limitN: Int = 30,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: ShaftApiV2.MostViewedResponse = if (cursor == null) {
            ShaftApiV2Client.service.mostViewed(type = type, limit = limitN)
        } else {
            ShaftApiV2Client.service.mostViewedByUrl(cursor)
        }
        // gson 解析 + 内容过滤挪 Default，保住 load 的 main-safe 契约。
        val items = withContext(Dispatchers.Default) {
            resp.items.mapNotNull { mapViewRankItem(it) }
        }
        return FeedPage(items, resp.next_url?.takeIf { it.isNotEmpty() })
    }

    companion object {
        /** item.bean → IllustFeedItem（跑在 Default、纯函数、零捕获）。 */
        private fun mapViewRankItem(item: ShaftApiV2.TrendingWorkItem): IllustFeedItem? {
            val json = item.bean ?: return null
            val bean = try {
                Shaft.sGson.fromJson(json, IllustsBean::class.java)
            } catch (e: Throwable) {
                Timber.tag("ViewRank").w(e, "skip malformed bean id=${item.target_id}")
                return null
            } ?: return null
            // 浏览量榜：pill 显浏览数（TrendingScoreFormat 支持 M，6457227→「6.5M」）；
            // payload 里的收藏/关注态是上报者的，清零让用户以自己名义收藏/关注（对齐 legacy ViewRankRepo）。
            bean.trendingScore = item.view_count.toFloat()
            bean.isIs_bookmarked = false
            bean.user?.isIs_followed = false
            FollowStateBackfill.markIllustUntrusted(bean.id.toLong())
            return IllustFeedItem.fromBean(bean, bookmarkStateUnknown = true)
        }
    }
}
