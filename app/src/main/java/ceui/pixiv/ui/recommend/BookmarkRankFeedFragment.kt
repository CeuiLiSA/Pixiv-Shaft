package ceui.pixiv.ui.recommend

import android.os.Bundle
import android.view.View
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.loxia.Illust
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** ?ai=only —— 只看 AI 生成。null 即通榜(不带该 query)。 */
const val AI_ONLY = "only"

private const val ARG_AI = "bookmark_rank_ai"

/**
 * 全站收藏榜(feeds 框架版)。单作按 pixiv 总收藏数排(含 R-18),打自建 shaft-api-v2 的
 * discover/most-bookmarked;普通插画瀑布流 + 自带 toolbar(fragment_toolbar_feed),
 * 热度 pill 显收藏数。
 *
 * TemplateActivity 用同一个 Fragment 承载「收藏榜」「AI 榜」两个入口 —— 区别只是给服务端
 * 多带一个 ?ai=only(同 [ArtistRankFeedFragment] 承载画师榜/均分榜的做法)。年代榜/标签专区
 * 是另外的入口(「选择条 + bottom sheet」宿主,装 [YearRankIllustFeedFragment] /
 * [TagRankIllustFeedFragment]),但共用下面的 [BookmarkRankFeedSource]。
 *
 * 为什么 AI 值得单独一个入口:AI 作品占服务端库存 45%,但在收藏榜**头部几乎不存在**
 * (前 1000 名里 0.0%、前 1 万名里 0.6%)—— 天花板 72314 收藏 vs 非 AI 的 990150。
 * 所以「通榜 + AI 过滤开关」没有意义(头部本来就没 AI,过滤前后长得一样);真正有价值的是
 * 反过来给这 45% 一个独立榜单 —— 它们在别的榜里永远看不到,而其中 5 万个作品收藏过千。
 */
class BookmarkRankFeedFragment : IllustFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    // 只认 only,其余(含缺参)一律回落通榜——对齐 ArtistRankFeedFragment 的兜底。
    private val aiFilter: String? by lazy(LazyThreadSafetyMode.NONE) {
        if (requireArguments().getString(ARG_AI) == AI_ONLY) AI_ONLY else null
    }

    override val feedViewModel by feedViewModels {
        // 零捕获:先把 Fragment 属性取成局部 val 再进 source
        val ai = aiFilter
        BookmarkRankFeedSource(ai = ai)
    }

    // shaft-api-v2 的 next_url 是 shaft 绝对 URL,不是 app-api illust nextUrl;别漏进详情页 pager
    // (getNextIllust 拿它当 @Url 请求会拿到 MostBookmarkedResponse 形状,解析成空 IllustResponse)。
    override val detailContinuationCursor: String? get() = null

    // 榜单 bean 是第三方上报快照:is_bookmarked 被 source 伪造成 false、user.is_followed 是
    // 上报者的——都不可信,喂池会把当前用户更新的收藏/关注态盖回去(mergeKeepingExisting 不把
    // false 当空值,AppLevelStateHelper.fill 直接灌关注态)。同 WatchLaterFeedFragment 先例。
    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(
            if (aiFilter == AI_ONLY) R.string.ai_rank_title else R.string.bookmark_rank_title
        )
    }

    companion object {
        /** [ai] 传 [AI_ONLY] 即 AI 榜;传 null 即通榜。 */
        @JvmStatic
        fun newInstance(ai: String?): BookmarkRankFeedFragment {
            return BookmarkRankFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_AI, ai)
                }
            }
        }
    }
}

/**
 * 收藏榜数据源:shaft-api-v2 discover/most-bookmarked(首屏 mostBookmarked,翻页 mostBookmarkedByUrl)。
 * 响应不实现 KListShow(item.bean 是 JsonObject),用不了 PixivFeedSource,手写 [FeedSource]
 * (同浏览量榜 [ViewRankFeedSource])。
 *
 * [ai] / [year] / [tag] 为 null 时 Retrofit 不发该 query,即无筛选 —— 收藏榜、AI 榜、年代榜、
 * 标签专区共用本 source,只是带的 query 不同。零 Fragment 捕获(全是构造进来的局部值,
 * map 是伴生纯函数)。
 */
class BookmarkRankFeedSource(
    private val type: String = "illust",
    private val limitN: Int = 30,
    private val ai: String? = null,
    private val year: String? = null,
    private val tag: String? = null,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: ShaftApiV2.MostBookmarkedResponse = if (cursor == null) {
            ShaftApiV2Client.service.mostBookmarked(
                type = type, limit = limitN, ai = ai, year = year, tag = tag)
        } else {
            ShaftApiV2Client.service.mostBookmarkedByUrl(cursor)
        }
        // ⚠️ AI 榜必须让步全局「屏蔽 AI 作品」开关,否则开了那个开关的用户点进 AI 榜会看到
        // **整页空** —— 服务端每条都是 AI,客户端每条都被过滤掉。用户设的是「我平时不想看到
        // AI」,不是「我主动点开 AI 榜也不想看」。同 R18 专属榜单让步全局 R18 过滤的先例
        // (见 IllustFeedItem.passesContentFilters 的 skipR18Filter)。只让步 AI 这一条,
        // 屏蔽画师/标签/作品 ID 照常生效。局部 val:保持零捕获。
        val skipAi = ai == AI_ONLY
        // gson 解析 + 内容过滤挪 Default,保住 load 的 main-safe 契约。
        val items = withContext(Dispatchers.Default) {
            resp.items.mapNotNull { mapBookmarkRankItem(it, skipAi) }
        }
        return FeedPage(items, resp.next_url?.takeIf { it.isNotEmpty() })
    }

    companion object {
        /** item.bean → IllustFeedItem(跑在 Default、纯函数、零捕获)。 */
        private fun mapBookmarkRankItem(
            item: ShaftApiV2.TrendingWorkItem,
            skipAiFilter: Boolean,
        ): IllustFeedItem? {
            val json = item.bean ?: return null
            val bean = try {
                Shaft.sGson.fromJson(json, Illust::class.java)
            } catch (e: Throwable) {
                Timber.tag("BookmarkRank").w(e, "skip malformed bean id=${item.target_id}")
                return null
            } ?: return null
            // 收藏榜:pill 显 pixiv 总收藏数(TrendingScoreFormat 支持 K/M,990150→「990.2K」);
            // payload 里的收藏态是上报者的,清零让用户以自己名义收藏(对齐 ViewRankFeedSource)。
            return IllustFeedItem.of(
                bean.withTrendingScore(item.bookmark_count.toFloat()).withBookmarked(false),
                skipAiFilter = skipAiFilter,
            )
        }
    }
}
