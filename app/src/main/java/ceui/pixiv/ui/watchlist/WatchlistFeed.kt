package ceui.pixiv.ui.watchlist

import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.databinding.RecyWatchlistMangaBinding
import ceui.lisa.databinding.RecyWatchlistNovelBinding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.Client
import ceui.loxia.WatchlistSeries
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.detail.IllustSeriesFragment
import ceui.pixiv.ui.novel.NovelSeriesFragment
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.utils.ppppx
import com.bumptech.glide.Glide

/**
 * 「追更列表」的漫画 / 小说条目。持不可变的 loxia [WatchlistSeries]。
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
     * 卡片间距：recy_watchlist_* 的根 CardView 不自带 margin，全靠 decoration 撑开
     *（legacy 是 ListFragment.verticalRecyclerView() 默认挂的 12dp，feeds 的 onListReady 默认
     * 什么都不挂，得自己来）。12dp 与同族的 NovelFeedFragment / UserFeedFragment 一致。
     */
    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    /** 空态文案：追更列表为空是常态（没追过任何系列），别退化成通用的「居然啥也没有」。 */
    override val emptyStateText: CharSequence
        get() = getString(R.string.string_186)
}

/**
 * 追更「漫画」tab。
 *
 * 端点 `v1/watchlist/manga`（loxia [ceui.loxia.API.getWatchlistManga]，与 legacy
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
 * 追更漫画卡（复用 legacy 的 recy_watchlist_manga，视觉零变化）。
 *
 * 点卡片 / 点「查看最新」都进漫画系列详情 —— 这不是我合并的，legacy WatchlistMangaAdapter
 * 两个监听器本就跳同一处（只有小说侧的「阅读最新」才真去开最新一话）。
 */
internal fun WatchlistFeedFragment.watchlistMangaRenderer():
        FeedRenderer<WatchlistMangaFeedItem, RecyWatchlistMangaBinding> =
    feedRenderer<WatchlistMangaFeedItem, RecyWatchlistMangaBinding>(
        inflate = RecyWatchlistMangaBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClick { openMangaSeries(cell.item.series) }
            cell.binding.viewLatest.setOnClick { openMangaSeries(cell.item.series) }
            cell.binding.author.setOnClick { openSeriesAuthor(cell.item.series) }
            cell.binding.userHead.setOnClick { openSeriesAuthor(cell.item.series) }
        },
        recycle = { cell ->
            cell.binding.cover.clearGlideOnRecycle()
            cell.binding.userHead.clearGlideOnRecycle()
        },
    ) { cell ->
        bindWatchlistCard(
            series = cell.item.series,
            title = cell.binding.title,
            author = cell.binding.author,
            lastDate = cell.binding.lastDate,
            contentCount = cell.binding.contentCount,
            cover = cell.binding.cover,
            userHead = cell.binding.userHead,
            actionButton = cell.binding.viewLatest,
        )
    }

/**
 * 追更小说卡（复用 legacy 的 recy_watchlist_novel，视觉零变化）。
 *
 * 与漫画卡的唯一差别：「阅读最新」按 [WatchlistSeries.latest_content_id] 直接开最新一话
 * （那是**作品** id，不是系列 id），点卡片才进系列页。
 */
internal fun WatchlistFeedFragment.watchlistNovelRenderer():
        FeedRenderer<WatchlistNovelFeedItem, RecyWatchlistNovelBinding> =
    feedRenderer<WatchlistNovelFeedItem, RecyWatchlistNovelBinding>(
        inflate = RecyWatchlistNovelBinding::inflate,
        create = { cell ->
            cell.binding.root.setOnClick { openNovelSeries(cell.item.series) }
            cell.binding.readLatest.setOnClick {
                // legacy 在 latest_content_id 为 null 时会 NPE（`target.latest_content_id!!`）；
                // 这里静默忽略——屏蔽态本就不该走到（按钮已 INVISIBLE），非屏蔽态缺这个字段
                // 是服务端的边角，没有「最新一话」可开，不值得崩。
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
        bindWatchlistCard(
            series = cell.item.series,
            title = cell.binding.title,
            author = cell.binding.author,
            lastDate = cell.binding.lastDate,
            contentCount = cell.binding.contentCount,
            cover = cell.binding.cover,
            userHead = cell.binding.userHead,
            actionButton = cell.binding.readLatest,
        )
    }

/**
 * 两张卡共用的绑定（两份布局的字段完全同构，只有动作按钮的 id 不同）。
 *
 * 屏蔽态（[WatchlistSeries.isMasked]）按 legacy 语义：只显示 mask_text、清空其余文案、
 * 隐藏封面与动作按钮。点击监听在 create 阶段挂一次，这里不重复摘挂——各 open* 自带
 * 屏蔽守卫，比 legacy 每次 bind 都 `setOnClickListener {}` 覆盖一遍更省。
 */
private fun bindWatchlistCard(
    series: WatchlistSeries,
    title: android.widget.TextView,
    author: android.widget.TextView,
    lastDate: android.widget.TextView,
    contentCount: android.widget.TextView,
    cover: android.widget.ImageView,
    userHead: android.widget.ImageView,
    actionButton: View,
) {
    if (series.isMasked) {
        title.text = series.mask_text
        author.text = ""
        lastDate.text = ""
        contentCount.text = ""
        // INVISIBLE 而非 GONE：保住卡片高度，屏蔽条目不会比邻居矮一截（对齐 legacy）
        actionButton.visibility = View.INVISIBLE
        cover.visibility = View.INVISIBLE
        // 头像也要藏：复用的 holder 从正常条目重绑到屏蔽条目时，上一条画师的头像会原样留着
        userHead.visibility = View.INVISIBLE
        return
    }
    title.text = series.title
    author.text = series.user?.name ?: ""
    // ISO 串只取日期部分（legacy 是在模型 getter 里 substring(0,10)，那份 getter 还会对
    // null 串抛 NPE；这里改成读时安全截取）
    lastDate.text = series.last_published_content_datetime?.take(10) ?: ""
    contentCount.text = contentCount.context.getString(
        R.string.episode_number, series.published_content_count,
    )
    actionButton.visibility = View.VISIBLE
    cover.visibility = View.VISIBLE
    userHead.visibility = View.VISIBLE
    Glide.with(cover).load(GlideUtil.getUrl(series.url)).into(cover)
    series.user?.let { Glide.with(userHead).load(GlideUtil.getHead(it)).into(userHead) }
}

private fun WatchlistFeedFragment.openMangaSeries(series: WatchlistSeries) {
    if (series.isMasked) return
    startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
        putExtra(IllustSeriesFragment.ARG_SERIES_ID, series.id)
        putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画系列详情")
    })
}

private fun WatchlistFeedFragment.openNovelSeries(series: WatchlistSeries) {
    if (series.isMasked) return
    startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
        putExtra(NovelSeriesFragment.ARG_SERIES_ID, series.id)
        putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列")
    })
}

private fun WatchlistFeedFragment.openSeriesAuthor(series: WatchlistSeries) {
    if (series.isMasked) return
    val userId = series.user?.id ?: return
    startActivity(Intent(requireContext(), UActivity::class.java).apply {
        putExtra(Params.USER_ID, userId)
    })
}
