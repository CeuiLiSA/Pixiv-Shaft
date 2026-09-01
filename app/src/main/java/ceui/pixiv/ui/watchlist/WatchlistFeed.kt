package ceui.pixiv.ui.watchlist

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.databinding.CellSeriesV3Binding
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.api.Client
import ceui.pixiv.api.model.WatchlistSeries
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.detail.IllustSeriesFragment
import ceui.pixiv.ui.novel.NovelSeriesFragment
import ceui.pixiv.ui.series.SeriesCard
import ceui.pixiv.ui.series.SeriesCardModel
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.utils.ppppx
import com.bumptech.glide.Glide
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 「追更列表」的漫画 / 小说条目。持不可变的 [WatchlistSeries]。
 *
 * 漫画与小说分成两个类而不是共用一个带 flag 的类：[ceui.pixiv.feeds.FeedItem] 的身份是
 * (具体类型, feedKey)，两边的系列 id 各自独立、可能重号，共用一个类会让它们在同一张列表里撞身份。
 * 虽然当前两个 tab 各自一张列表、撞不到，但这条约束不该靠「碰巧没混排」维系。
 */
data class WatchlistMangaFeedItem(val series: WatchlistSeries) : FeedItem {
    override val feedKey: Any get() = series.id
}

data class WatchlistNovelFeedItem(val series: WatchlistSeries) : FeedItem {
    override val feedKey: Any get() = series.id
}

/**
 * 追更列表两个 tab 的共享基类（feeds 框架版，替代 legacy FragmentWatchlistManga /
 * FragmentWatchlistNovel + NetListFragment + WatchlistMangaAdapter / WatchlistNovelAdapter）。
 *
 * 宿主是 [ceui.lisa.fragments.FragmentCollection]（type 3）的 pager，两个 tab 各一个实例。
 * 无 toolbar（对齐 legacy 的 `showToolbar() = false`，标题由宿主的 TabLayout 给）。
 *
 * 懒加载：`autoLoad = false` + 宿主 pager 的 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT。
 * 这两个页曾是 FragmentCollection 里最后一对 legacy BaseLazyFragment，逼得那边的 pagerBehavior
 * 必须为 type 3 留一个 USER_VISIBLE_HINT 特例；迁完即可拆掉那个特例。
 */
abstract class WatchlistFeedFragment : FeedFragment() {

    /**
     * 卡片间距：cell_series_v3 不自带 margin，全靠 decoration 撑开
     *（legacy 是 ListFragment.verticalRecyclerView() 默认挂的 12dp，feeds 的 onListReady 默认
     * 什么都不挂，得自己来）。12dp 与同族的 NovelFeedFragment / UserFeedFragment 一致。
     */
    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    /** 空态文案：追更列表为空是常态（没追过任何系列），别退化成通用的「居然啥也没有」。 */
    override val emptyStateText: CharSequence
        get() = getString(R.string.watchlist_empty)
}

/**
 * 追更「漫画」tab。
 *
 * 端点 `v1/watchlist/manga`（[ceui.pixiv.api.API.getWatchlistManga]，与 legacy
 * `AppApi.getWatchlistManga` 逐字对齐：路径之外不带任何 query），翻页走响应自带的 nextUrl。
 */
class WatchlistMangaFeedFragment : WatchlistFeedFragment() {

    override val feedViewModel by feedViewModels(autoLoad = false) {
        pixivFeedSource({ Client.appApi.getWatchlistManga() }) { resp, _ ->
            resp.series.map { WatchlistMangaFeedItem(it) }
        }
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(watchlistMangaRenderer())
    }
}

/**
 * 追更「小说」tab。端点 `v1/watchlist/novel`，其余同漫画 tab。
 */
class WatchlistNovelFeedFragment : WatchlistFeedFragment() {

    override val feedViewModel by feedViewModels(autoLoad = false) {
        pixivFeedSource({ Client.appApi.getWatchlistNovel() }) { resp, _ ->
            resp.series.map { WatchlistNovelFeedItem(it) }
        }
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(watchlistNovelRenderer())
    }
}

/**
 * 追更漫画卡:共用的 V3 系列卡([ceui.lisa.R.layout.cell_series_v3],与系列榜同一张)。
 *
 * 点卡片 / 点「查看最新话」都进漫画系列详情 —— 这不是我合并的,legacy WatchlistMangaAdapter
 * 两个监听器本就跳同一处(只有小说侧的「阅读最新话」才真去开最新一话)。
 */
internal fun WatchlistFeedFragment.watchlistMangaRenderer():
        FeedRenderer<WatchlistMangaFeedItem, CellSeriesV3Binding> =
    feedRenderer<WatchlistMangaFeedItem, CellSeriesV3Binding>(
        inflate = CellSeriesV3Binding::inflate,
        create = { cell ->
            SeriesCard.setup(cell.binding)
            cell.binding.root.setOnClick { openMangaSeries(cell.item.series) }
            cell.binding.action.setOnClick { openMangaSeries(cell.item.series) }
            cell.binding.author.setOnClick { openSeriesAuthor(cell.item.series) }
            cell.binding.userHead.setOnClick { openSeriesAuthor(cell.item.series) }
        },
        recycle = { cell ->
            cell.binding.cover.clearGlideOnRecycle()
            cell.binding.userHead.clearGlideOnRecycle()
        },
    ) { cell ->
        SeriesCard.bind(
            cell.binding,
            cell.item.series.toCardModel(requireContext(), R.string.view_latest_episode),
            Glide.with(this),
        )
    }

/**
 * 追更小说卡(同一张 V3 系列卡)。
 *
 * 与漫画卡的唯一差别:「阅读最新话」按 [WatchlistSeries.latest_content_id] 直接开最新一话
 * (那是**作品** id,不是系列 id),点卡片才进系列页。
 */
internal fun WatchlistFeedFragment.watchlistNovelRenderer():
        FeedRenderer<WatchlistNovelFeedItem, CellSeriesV3Binding> =
    feedRenderer<WatchlistNovelFeedItem, CellSeriesV3Binding>(
        inflate = CellSeriesV3Binding::inflate,
        create = { cell ->
            SeriesCard.setup(cell.binding)
            cell.binding.root.setOnClick { openNovelSeries(cell.item.series) }
            cell.binding.action.setOnClick {
                // legacy 在 latest_content_id 为 null 时会 NPE(`target.latest_content_id!!`);
                // 这里静默忽略——屏蔽态本就不该走到(按钮已 GONE),非屏蔽态缺这个字段
                // 是服务端的边角,没有「最新一话」可开,不值得崩。
                val latest = cell.item.series.latest_content_id ?: return@setOnClick
                PixivOperate.getNovelByID(latest, requireContext(), null)
            }
            cell.binding.author.setOnClick { openSeriesAuthor(cell.item.series) }
            cell.binding.userHead.setOnClick { openSeriesAuthor(cell.item.series) }
        },
        recycle = { cell ->
            cell.binding.cover.clearGlideOnRecycle()
            cell.binding.userHead.clearGlideOnRecycle()
        },
    ) { cell ->
        SeriesCard.bind(
            cell.binding,
            cell.item.series.toCardModel(requireContext(), R.string.read_latest_episode),
            Glide.with(this),
        )
    }

/**
 * pixiv 追更条目 → 系列卡显示模型。屏蔽态([WatchlistSeries.isMasked])只带 mask_text,
 * 卡片按 legacy 语义只显示那句话。ISO 时间串只取日期部分(legacy 是在模型 getter 里
 * substring(0,10),那份 getter 还会对 null 串抛 NPE;这里改成读时安全截取)。
 */
private fun WatchlistSeries.toCardModel(context: Context, actionRes: Int): SeriesCardModel {
    if (isMasked) {
        return SeriesCardModel(
            title = "", coverUrl = null, countText = "", subtitle = "", subtitleAccent = false,
            authorName = "", authorHeadUrl = null, maskText = mask_text,
        )
    }
    val date = last_published_content_datetime?.take(10)
    return SeriesCardModel(
        title = title,
        coverUrl = url,
        countText = context.getString(R.string.episode_number, published_content_count),
        subtitle = if (date.isNullOrEmpty()) "" else context.getString(R.string.series_updated_at, date),
        subtitleAccent = false,
        authorName = user?.name ?: "",
        authorHeadUrl = user?.profile_image_urls?.medium,
        actionText = context.getString(actionRes),
    )
}

private fun WatchlistFeedFragment.openMangaSeries(series: WatchlistSeries) {
    if (series.isMasked) return
    startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
        putExtra(IllustSeriesFragment.ARG_SERIES_ID, series.id)
        putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.MANGA_SERIES_DETAIL.key)
    })
}

private fun WatchlistFeedFragment.openNovelSeries(series: WatchlistSeries) {
    if (series.isMasked) return
    startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
        putExtra(NovelSeriesFragment.ARG_SERIES_ID, series.id)
        putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_SERIES.key)
    })
}

private fun WatchlistFeedFragment.openSeriesAuthor(series: WatchlistSeries) {
    if (series.isMasked) return
    val userId = series.user?.id ?: return
    startActivity(Intent(requireContext(), UActivity::class.java).apply {
        putExtra(Params.USER_ID, userId)
    })
}
