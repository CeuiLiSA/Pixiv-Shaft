package ceui.pixiv.ui.recommend

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.databinding.ItemRankNoticeBinding
import ceui.lisa.databinding.CellSeriesV3Binding
import ceui.lisa.network.ShaftApiV2
import ceui.lisa.network.ShaftApiV2Client
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecoration
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.detail.IllustSeriesFragment
import ceui.pixiv.ui.novel.NovelSeriesFragment
import ceui.pixiv.ui.series.SeriesCard
import ceui.pixiv.ui.series.SeriesCardModel
import ceui.pixiv.utils.clearGlideOnRecycle
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 系列榜 — 打自建服务端 shaft-api-v2 的 discover/series。两个固定 tab:漫画 / 小说,
 * 系列按「系列内作品累计 pixiv 收藏数」排(含 R-18)。单 tab 是 [SeriesRankFeedFragment]。
 *
 * 宿主是 [TypeTabsRankFragment](toolbar + tabs + pager,FSPA + RESUME_ONLY_CURRENT +
 * 子 fragment autoLoad=false)。
 */
class SeriesRankFragment : TypeTabsRankFragment() {

    override val titleRes: Int get() = R.string.series_rank_title

    /** 漫画在前(系列心智更强)。 */
    override val types: List<String> get() = listOf(RankType.MANGA, RankType.NOVEL)

    override fun createPage(type: String): Fragment = SeriesRankFeedFragment.newInstance(type)

    companion object {
        @JvmStatic
        fun newInstance(): SeriesRankFragment = SeriesRankFragment()
    }
}

/**
 * 系列榜单条:系列元数据 + 封面缩略图 URL(已按类型从 cover_bean 里解析好,渲染时不再碰 JSON)。
 * feedKey 用 series_id;漫画 / 小说系列 id 各自独立、可能重号,但两 tab 各一张列表,同一列表
 * 内 (type, series_id) 不会撞 —— 仍把 type 编进 key 以免将来混排。
 */
data class SeriesRankFeedItem(
    val type: String,
    val seriesId: Long,
    val title: String,
    val workCount: Int,
    val totalBookmarks: Int,
    val userId: Long,
    val userName: String,
    val userHeadUrl: String?,
    val coverUrl: String?,
    /** 榜单名次(1 起,= 服务端 offset + 页内序号),卡片左上徽标。 */
    val rank: Int,
) : FeedItem {
    override val feedKey: Any get() = "$type:$seriesId"
}

/**
 * 列表首位的一行浅色提示(服务端 complete=false → 「榜单统计中」)。只在首屏且服务端说
 * 未完成时由 source 塞进去;单例 key,列表里至多一条。
 */
data class RankNoticeItem(val textRes: Int) : FeedItem {
    override val feedKey: Any get() = "rank_notice"
}

/**
 * 系列榜的 feed 子页(漫画 / 小说各一个实例,由 [SeriesRankFragment] 的 pager 装)。
 * 共用的 V3 系列卡 cell_series_v3(与追更列表同一张):封面 + 名次徽标 / 系列标题 / 「N话」chip +
 * 「累计收藏 M」/ 画师头像+名字;
 * 整卡点击进系列页(manga → 「漫画系列详情」IllustSeriesFragment,novel → 「小说系列」
 * NovelSeriesFragment),头像 / 画师名进画师页。
 *
 * `autoLoad = false`:宿主 pager 是 FSPA + RESUME_ONLY_CURRENT,只有可见 tab 拉首屏。
 */
class SeriesRankFeedFragment : FeedFragment() {

    private val type: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_TYPE) ?: RankType.MANGA
    }

    /** 封面 / 头像的 Glide 请求管理器,建一次复用(理由见 NovelFeedFragment.novelGlide)。 */
    private val seriesGlide: RequestManager by lazy { Glide.with(this) }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获:只捕获局部值,不把 Fragment 钉进 VM。
        val type = type
        SeriesRankFeedSource(type)
    }

    // 内嵌 pager tab(无底栏)时,列表底部补手势条 inset;宿主 toolbar 不归本页管。
    override val applyBottomSafeInset: Boolean = true

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(noticeRenderer(), seriesRenderer())
    }

    override fun onListReady(listView: RecyclerView) {
        // 卡间距:cell_series_v3 不自带 margin,全靠 decoration 撑开(同追更列表)。
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    private fun noticeRenderer() =
        feedRenderer<RankNoticeItem, ItemRankNoticeBinding>(
            inflate = ItemRankNoticeBinding::inflate,
            fullSpan = true,
        ) { cell -> cell.binding.noticeText.setText(cell.item.textRes) }

    private fun seriesRenderer() =
        feedRenderer<SeriesRankFeedItem, CellSeriesV3Binding>(
            inflate = CellSeriesV3Binding::inflate,
            create = { cell ->
                SeriesCard.setup(cell.binding)
                cell.binding.root.setOnClick { cell.itemOrNull?.let { openSeries(it) } }
                cell.binding.userHead.setOnClick { cell.itemOrNull?.let { openAuthor(it) } }
                cell.binding.author.setOnClick { cell.itemOrNull?.let { openAuthor(it) } }
            },
            recycle = { cell ->
                cell.binding.cover.clearGlideOnRecycle()
                cell.binding.userHead.clearGlideOnRecycle()
            },
        ) { cell ->
            val item = cell.item
            SeriesCard.bind(
                cell.binding,
                SeriesCardModel(
                    title = item.title,
                    coverUrl = item.coverUrl,
                    countText = getString(R.string.episode_number, item.workCount),
                    subtitle = getString(R.string.series_total_bookmarks, formatRankCount(item.totalBookmarks)),
                    subtitleAccent = true,
                    authorName = item.userName,
                    authorHeadUrl = item.userHeadUrl,
                    rank = item.rank,
                ),
                seriesGlide,
            )
        }

    /** 整卡点击:manga → 漫画系列详情(IllustSeriesFragment),novel → 小说系列(NovelSeriesFragment)。 */
    private fun openSeries(item: SeriesRankFeedItem) {
        if (item.seriesId == 0L) return
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        if (item.type == RankType.NOVEL) {
            intent.putExtra(NovelSeriesFragment.ARG_SERIES_ID, item.seriesId)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_SERIES.key)
        } else {
            intent.putExtra(IllustSeriesFragment.ARG_SERIES_ID, item.seriesId)
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.MANGA_SERIES_DETAIL.key)
        }
        startActivity(intent)
    }

    private fun openAuthor(item: SeriesRankFeedItem) {
        if (item.userId == 0L) return
        startActivity(Intent(requireContext(), UActivity::class.java).apply {
            putExtra(Params.USER_ID, item.userId)
        })
    }

    companion object {
        private const val ARG_TYPE = "series_rank_type"

        /** [type] 是 [RankType.MANGA] / [RankType.NOVEL],服务端 enum 语义,别本地化。 */
        @JvmStatic
        fun newInstance(type: String): SeriesRankFeedFragment {
            return SeriesRankFeedFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, type)
                }
            }
        }
    }
}

/**
 * 系列榜数据源:shaft-api-v2 discover/series(首屏 discoverSeries,翻页 discoverSeriesByUrl)。
 * 响应不实现 KListShow(cover_bean 是原始 JSON),手写 [FeedSource](同 [ArtistRankFeedSource])。
 *
 * 首屏且服务端 `complete == false`(衍生表回填未完,榜可能不全)时,在列表最前面塞一条
 * [RankNoticeItem]「榜单统计中」;翻页不再重复塞。零 Fragment 捕获。
 */
class SeriesRankFeedSource(
    /** [RankType.MANGA] | [RankType.NOVEL]。 */
    private val type: String,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val resp: ShaftApiV2.SeriesRankResponse = if (cursor == null) {
            ShaftApiV2Client.service.discoverSeries(type = type)
        } else {
            ShaftApiV2Client.service.discoverSeriesByUrl(cursor)
        }
        val isNovel = type == RankType.NOVEL
        // gson 解析 cover_bean 挪 Default,保住 load 的 main-safe 契约。
        val items = withContext(Dispatchers.Default) {
            val base = (resp.offset ?: 0) + 1
            resp.items.orEmpty().mapIndexedNotNull { i, it -> mapSeriesItem(it, type, isNovel, base + i) }
        }
        val withNotice = if (cursor == null && resp.complete == false && items.isNotEmpty()) {
            listOf(RankNoticeItem(R.string.rank_incomplete_notice)) + items
        } else {
            items
        }
        return FeedPage(withNotice, resp.next_url?.takeIf { it.isNotEmpty() })
    }

    companion object {
        /** SeriesRankItem → SeriesRankFeedItem(跑在 Default、纯函数、零捕获)。 */
        private fun mapSeriesItem(
            item: ShaftApiV2.SeriesRankItem,
            type: String,
            isNovel: Boolean,
            rank: Int,
        ): SeriesRankFeedItem? {
            // series_id 缺失的脏条目会全挤成同一个身份(0),被框架 dedupByIdentity 折叠成一条,直接跳过。
            if (item.series_id == 0L) return null
            val coverJson = item.cover_bean as? JsonObject
            val coverUrl = coverJson?.let { json ->
                try {
                    if (isNovel) {
                        Shaft.sGson.fromJson(json, Novel::class.java)?.resolvedCoverUrl()
                    } else {
                        Shaft.sGson.fromJson(json, Illust::class.java)?.image_urls?.let {
                            it.square_medium ?: it.medium ?: it.large
                        }
                    }
                } catch (e: Throwable) {
                    // 封面 bean 坏了只丢封面,不丢整条 —— 系列标题 / 画师 / 计数都还在服务端字段里。
                    Timber.tag("SeriesRank").w(e, "skip malformed cover_bean series=${item.series_id}")
                    null
                }
            }
            val user = item.user
            return SeriesRankFeedItem(
                type = type,
                seriesId = item.series_id,
                title = item.title.orEmpty(),
                workCount = item.work_count,
                totalBookmarks = item.total_bookmarks,
                userId = user?.id ?: 0L,
                userName = user?.name ?: user?.account.orEmpty(),
                userHeadUrl = user?.profile_image_urls?.medium,
                coverUrl = coverUrl,
                rank = rank,
            )
        }
    }
}
