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

/** ?ai=only —— 只看 AI 生成。null 即通榜(不带该 query)。 */
const val AI_ONLY = "only"

/** ?restrict=sfw —— 全年龄榜(服务端剔除 R-18)。null 即通榜(不带该 query)。 */
const val RESTRICT_SFW = "sfw"

private const val ARG_AI = "bookmark_rank_ai"
private const val ARG_RESTRICT = "bookmark_rank_restrict"

/**
 * 全站收藏榜宿主:插画 / 漫画 / 小说 三个类型 tab([TypeTabsRankFragment]),每个 tab 一个
 * [BookmarkRankIllustFeedFragment] / [BookmarkRankNovelFeedFragment],打自建 shaft-api-v2 的
 * discover/most-bookmarked?type=,单作按 pixiv 总收藏数排,热度 pill 显收藏数。
 *
 * TemplateActivity 用同一个 Fragment 承载「收藏榜」「AI 榜」「全年龄榜」三个入口 —— 区别只是
 * 给服务端多带 ?ai=only 或 ?restrict=sfw(同 [ArtistRankFeedFragment] 承载画师榜/均分榜的做法)。
 * AI 榜只有 插画 / 漫画 两个 tab:novel 没有 illust_ai_type 字段,带 ?ai 会被服务端 400。
 * 年代榜/标签专区是另外的入口(选择条 + bottom sheet),但共用下面的 [BookmarkRankFeedSource]。
 *
 * 为什么 AI 值得单独一个入口:AI 作品占服务端库存 45%,但在收藏榜**头部几乎不存在**
 * (前 1000 名里 0.0%、前 1 万名里 0.6%)—— 天花板 72314 收藏 vs 非 AI 的 990150。
 * 所以「通榜 + AI 过滤开关」没有意义(头部本来就没 AI,过滤前后长得一样);真正有价值的是
 * 反过来给这 45% 一个独立榜单 —— 它们在别的榜里永远看不到,而其中 5 万个作品收藏过千。
 */
class BookmarkRankFragment : TypeTabsRankFragment() {

    // 只认 only,其余(含缺参)一律回落通榜——对齐 ArtistRankFeedFragment 的兜底。
    private val aiFilter: String? by lazy(LazyThreadSafetyMode.NONE) {
        if (requireArguments().getString(ARG_AI) == AI_ONLY) AI_ONLY else null
    }
    private val restrict: String? by lazy(LazyThreadSafetyMode.NONE) {
        if (requireArguments().getString(ARG_RESTRICT) == RESTRICT_SFW) RESTRICT_SFW else null
    }

    override val titleRes: Int
        get() = when {
            aiFilter == AI_ONLY -> R.string.ai_rank_title
            restrict == RESTRICT_SFW -> R.string.sfw_rank_title
            else -> R.string.bookmark_rank_title
        }

    override val types: List<String>
        get() = if (aiFilter == AI_ONLY) RankType.ILLUST_MANGA else RankType.ALL

    override fun createPage(type: String): Fragment = if (type == RankType.NOVEL) {
        BookmarkRankNovelFeedFragment.newInstance(restrict = restrict)
    } else {
        BookmarkRankIllustFeedFragment.newInstance(type = type, ai = aiFilter, restrict = restrict)
    }

    companion object {
        /**
         * [ai] 传 [AI_ONLY] 即 AI 榜;[restrict] 传 [RESTRICT_SFW] 即全年龄榜;都传 null 即通榜。
         */
        @JvmStatic
        fun newInstance(ai: String?, restrict: String?): BookmarkRankFragment {
            return BookmarkRankFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_AI, ai)
                    putString(ARG_RESTRICT, restrict)
                }
            }
        }
    }
}

/**
 * 收藏榜的插画 / 漫画 feed 子页(无 toolbar,toolbar 在宿主)。收藏榜 / AI 榜 / 全年龄榜的
 * 插画·漫画 tab,以及标签专区 / 年代榜按类型切出来的页面都用它,只是构造进
 * [BookmarkRankFeedSource] 的 query 不同。
 *
 * `autoLoad = false`:宿主是 RESUME_ONLY_CURRENT 的 ViewPager,或 replace 进来即 RESUMED 的
 * 单 feed 容器,首屏都由 FeedFragment.onResume 的 ensureLoaded 拉起 —— 只有可见页才打网络
 * (读端点 120 req/min/IP + CN 运营商级 NAT,多 tab 齐射会把整个 NAT 后的用户打成 429)。
 */
class BookmarkRankIllustFeedFragment : IllustFeedFragment() {

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获:只捕获局部值,不把 Fragment 钉进 VM。
        val args = requireArguments()
        val type = args.getString(ARG_TYPE) ?: RankType.ILLUST
        val ai = args.getString(ARG_AI)
        val year = args.getString(ARG_YEAR)
        val tag = args.getString(ARG_TAG)
        val restrict = args.getString(ARG_RESTRICT)
        BookmarkRankFeedSource(type = type, ai = ai, year = year, tag = tag, restrict = restrict)
    }

    // shaft-api-v2 的 next_url 是 shaft 绝对 URL,不是 app-api illust nextUrl;别漏进详情页 pager
    // (getNextIllust 拿它当 @Url 请求会拿到 MostBookmarkedResponse 形状,解析成空 IllustResponse)。
    override val detailContinuationCursor: String? get() = null

    // 榜单 bean 是第三方上报快照:is_bookmarked 被 source 伪造成 false、user.is_followed 是
    // 上报者的——都不可信,喂池会把当前用户更新的收藏/关注态盖回去(mergeKeepingExisting 不把
    // false 当空值,AppLevelStateHelper.fill 直接灌关注态)。同 WatchLaterFeedFragment 先例。
    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    companion object {
        private const val ARG_TYPE = "bookmark_rank_type"
        private const val ARG_YEAR = "bookmark_rank_year"
        private const val ARG_TAG = "bookmark_rank_tag"

        /**
         * [type] 是 [RankType.ILLUST] / [RankType.MANGA](服务端 enum);[ai] / [year] / [tag] /
         * [restrict] 为 null 即不带该 query。[tag] 是服务端原文 tag 名,别传 translated;
         * [year] 是 4 位年份字符串。
         */
        @JvmStatic
        fun newInstance(
            type: String,
            ai: String? = null,
            year: String? = null,
            tag: String? = null,
            restrict: String? = null,
        ): BookmarkRankIllustFeedFragment {
            return BookmarkRankIllustFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, type)
                    putString(ARG_AI, ai)
                    putString(ARG_YEAR, year)
                    putString(ARG_TAG, tag)
                    putString(ARG_RESTRICT, restrict)
                }
            }
        }
    }
}

/**
 * 收藏榜的小说 feed 子页:复用 [NovelFeedFragment] 的主力小说卡(热度分经 NovelFeedItem.trendingScore
 * 露 pill),数据同样走 [BookmarkRankFeedSource](type=novel)。没有 ai 参数:novel 不支持
 * ai 筛选(服务端 400),AI 榜压根不建小说 tab。其余约定同 [BookmarkRankIllustFeedFragment]。
 */
class BookmarkRankNovelFeedFragment : NovelFeedFragment() {

    override val feedViewModel by feedViewModels(autoLoad = false) {
        val args = requireArguments()
        val year = args.getString(ARG_YEAR)
        val tag = args.getString(ARG_TAG)
        val restrict = args.getString(ARG_RESTRICT)
        BookmarkRankFeedSource(type = RankType.NOVEL, year = year, tag = tag, restrict = restrict)
    }

    companion object {
        private const val ARG_YEAR = "bookmark_rank_year"
        private const val ARG_TAG = "bookmark_rank_tag"

        @JvmStatic
        fun newInstance(
            year: String? = null,
            tag: String? = null,
            restrict: String? = null,
        ): BookmarkRankNovelFeedFragment {
            return BookmarkRankNovelFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_YEAR, year)
                    putString(ARG_TAG, tag)
                    putString(ARG_RESTRICT, restrict)
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
 * [ai] / [year] / [tag] / [restrict] 为 null 时 Retrofit 不发该 query,即无筛选 —— 收藏榜、AI 榜、
 * 全年龄榜、年代榜、标签专区共用本 source,只是带的 query 不同。[type] 决定 bean 解析成插画卡
 * 还是小说卡(见 [toRankFeedItem])。零 Fragment 捕获(全是构造进来的局部值,map 是纯函数)。
 */
class BookmarkRankFeedSource(
    private val type: String = RankType.ILLUST,
    private val limitN: Int = 30,
    private val ai: String? = null,
    private val year: String? = null,
    private val tag: String? = null,
    private val restrict: String? = null,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: ShaftApiV2.MostBookmarkedResponse = if (cursor == null) {
            ShaftApiV2Client.service.mostBookmarked(
                type = type, limit = limitN, ai = ai, year = year, tag = tag, restrict = restrict)
        } else {
            ShaftApiV2Client.service.mostBookmarkedByUrl(cursor)
        }
        // ⚠️ AI 榜必须让步全局「屏蔽 AI 作品」开关,否则开了那个开关的用户点进 AI 榜会看到
        // **整页空** —— 服务端每条都是 AI,客户端每条都被过滤掉。用户设的是「我平时不想看到
        // AI」,不是「我主动点开 AI 榜也不想看」。同 R18 专属榜单让步全局 R18 过滤的先例
        // (见 IllustFeedItem.passesContentFilters 的 skipR18Filter)。只让步 AI 这一条,
        // 屏蔽画师/标签/作品 ID 照常生效。局部 val:保持零捕获。
        val skipAi = ai == AI_ONLY
        val type = type
        // gson 解析 + 内容过滤挪 Default,保住 load 的 main-safe 契约。
        // 收藏榜:pill 显 pixiv 总收藏数(TrendingScoreFormat 支持 K/M,990150→「990.2K」)。
        val items = withContext(Dispatchers.Default) {
            resp.items.mapNotNull {
                it.toRankFeedItem(type, it.bookmark_count.toFloat(), skipAi, "BookmarkRank")
            }
        }
        return FeedPage(items, resp.next_url?.takeIf { it.isNotEmpty() })
    }
}
