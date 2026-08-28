package ceui.pixiv.ui.recommend

import android.os.Bundle
import ceui.lisa.activities.Shaft
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** shaft-api-v2 榜单 `type` 的服务端 enum(不是展示文案,别本地化)。 */
internal const val RANK_TYPE_ILLUST = "illust"
internal const val RANK_TYPE_MANGA = "manga"
internal const val RANK_TYPE_NOVEL = "novel"

/** most-bookmarked 的 `length` enum(仅 novel):short <2 万字 / medium 2–5 万 / long ≥5 万。 */
internal const val NOVEL_LENGTH_LONG = "long"
internal const val NOVEL_LENGTH_MEDIUM = "medium"
internal const val NOVEL_LENGTH_SHORT = "short"

private const val ARG_TYPE = "filtered_rank_type"
private const val ARG_MONTH = "filtered_rank_month"
private const val ARG_LENGTH = "filtered_rank_length"

/**
 * 「新作榜」(month)/「长篇小说榜」(length)共用的收藏榜数据源:shaft-api-v2
 * discover/most-bookmarked 带 `month` / `length` 筛选(首屏 mostBookmarked,翻页
 * mostBookmarkedByUrl)。与既有 [BookmarkRankFeedSource] 是同一端点的不同 query 组合,
 * 单独一份是为了不去动收藏榜 / 年代榜那条正在被并行改的链路。
 *
 * 响应的 `complete=false`(衍生表回填未完)不在这里处理:榜单内容照常可用,只是可能不全。
 * 零 Fragment 捕获(全是构造进来的局部值,map 是顶层纯函数)。
 */
class FilteredBookmarkRankFeedSource(
    /** [RANK_TYPE_ILLUST] | [RANK_TYPE_MANGA] | [RANK_TYPE_NOVEL]。 */
    private val type: String,
    /** `YYYY-MM`,null = 不按月筛。 */
    private val month: String? = null,
    /** 仅 type=novel 时发;其余类型即便传了也不带给服务端。 */
    private val length: String? = null,
    private val limitN: Int = 30,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: ShaftApiV2.MostBookmarkedResponse = if (cursor == null) {
            ShaftApiV2Client.service.mostBookmarked(
                type = type,
                limit = limitN,
                month = month,
                length = length?.takeIf { type == RANK_TYPE_NOVEL },
            )
        } else {
            ShaftApiV2Client.service.mostBookmarkedByUrl(cursor)
        }
        val isNovel = type == RANK_TYPE_NOVEL
        // gson 解析 + 内容过滤挪 Default,保住 load 的 main-safe 契约。
        val items = withContext(Dispatchers.Default) {
            resp.items.mapNotNull { item ->
                if (isNovel) mapFilteredNovelItem(item) else mapFilteredIllustItem(item)
            }
        }
        return FeedPage(items, resp.next_url?.takeIf { it.isNotEmpty() })
    }
}

/** item.bean → IllustFeedItem(跑在 Default、纯函数、零捕获)。pill 显 pixiv 总收藏数。 */
private fun mapFilteredIllustItem(item: ShaftApiV2.TrendingWorkItem): IllustFeedItem? {
    val json = item.bean ?: return null
    val bean = try {
        Shaft.sGson.fromJson(json, Illust::class.java)
    } catch (e: Throwable) {
        Timber.tag("FilteredRank").w(e, "skip malformed illust bean id=${item.target_id}")
        return null
    } ?: return null
    // payload 里的收藏态是上报者的,清零让用户以自己名义收藏(对齐 BookmarkRankFeedSource)。
    return IllustFeedItem.of(
        bean.withTrendingScore(item.bookmark_count.toFloat()).withBookmarked(false),
    )
}

/** item.bean → NovelFeedItem。热度分(收藏数)单独带进 NovelFeedItem,不是 Novel 的字段。 */
private fun mapFilteredNovelItem(item: ShaftApiV2.TrendingWorkItem): NovelFeedItem? {
    val json = item.bean ?: return null
    val novel = try {
        Shaft.sGson.fromJson(json, Novel::class.java)
    } catch (e: Throwable) {
        Timber.tag("FilteredRank").w(e, "skip malformed novel bean id=${item.target_id}")
        return null
    } ?: return null
    return NovelFeedItem.of(novel.copy(is_bookmarked = false), item.bookmark_count.toFloat())
}

/**
 * 新作榜的插画 / 漫画 tab(无 toolbar,toolbar 在宿主 [MonthRankFragment])。
 *
 * `autoLoad = false`:宿主是三 tab ViewPager(FSPA + RESUME_ONLY_CURRENT),只有可见 tab
 * 才拉首屏,防三枪齐射吃掉读端点配额(论证见 [YearRankIllustFeedFragment])。
 */
class FilteredBookmarkRankIllustFeedFragment : IllustFeedFragment() {

    private val type: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_TYPE) ?: RANK_TYPE_ILLUST
    }
    private val month: String? by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_MONTH)
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获:只捕获局部值,不把 Fragment 钉进 VM。
        val type = type
        val month = month
        FilteredBookmarkRankFeedSource(type = type, month = month)
    }

    // shaft-api-v2 的 next_url 是 shaft 绝对 URL,不是 app-api illust nextUrl;别漏进详情页 pager
    // (getNextIllust 拿它当 @Url 请求会拿到 MostBookmarkedResponse 形状,解析成空 IllustResponse)。
    override val detailContinuationCursor: String? get() = null

    // 榜单 bean 是第三方上报快照:is_bookmarked 被 source 伪造成 false、user.is_followed 是
    // 上报者的——都不可信,喂池会把当前用户更新的收藏/关注态盖回去。同 YearRankIllustFeedFragment。
    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    companion object {
        /** [type] 是 [RANK_TYPE_ILLUST] / [RANK_TYPE_MANGA];[month] 是 `YYYY-MM`,服务端 enum 语义。 */
        @JvmStatic
        fun newInstance(type: String, month: String?): FilteredBookmarkRankIllustFeedFragment {
            return FilteredBookmarkRankIllustFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, type)
                    putString(ARG_MONTH, month)
                }
            }
        }
    }
}

/**
 * 新作榜的小说 tab / 长篇小说榜的三档 tab(无 toolbar)。复用 [NovelFeedFragment] 的主力
 * 小说卡(热度 pill 显收藏数),同 [HotWorksNovelFeedFragment]。`autoLoad = false` 理由同上。
 */
class FilteredBookmarkRankNovelFeedFragment : NovelFeedFragment() {

    private val month: String? by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_MONTH)
    }
    private val length: String? by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_LENGTH)
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        val month = month
        val length = length
        FilteredBookmarkRankFeedSource(type = RANK_TYPE_NOVEL, month = month, length = length)
    }

    companion object {
        /** [month] 是 `YYYY-MM`;[length] 是 [NOVEL_LENGTH_LONG] 等三档。都可为 null(不筛)。 */
        @JvmStatic
        fun newInstance(month: String?, length: String?): FilteredBookmarkRankNovelFeedFragment {
            return FilteredBookmarkRankNovelFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MONTH, month)
                    putString(ARG_LENGTH, length)
                }
            }
        }
    }
}
