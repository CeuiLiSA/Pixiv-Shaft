package ceui.pixiv.ui.search

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.RecyNovelBinding
import ceui.lisa.model.ListNovel
import ceui.loxia.Novel
import ceui.loxia.appServices
import ceui.lisa.repo.SearchNovelRepo
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.lisa.viewmodel.SearchModel
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.awaitFirstValue
import ceui.pixiv.ui.common.openUserActivity
import ceui.pixiv.ui.novel.NovelSeriesFragment
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import io.reactivex.functions.Function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ceui.pixiv.ui.usage.observeNana7miQuotaNotice

/**
 * 搜索「小说」tab（feeds 框架版，替代 legacy FragmentSearchNovel + SearchNovelRepo + NAdapter）。
 * 卡片复用 [NovelFeedFragment]。数据源包裹既有 [SearchNovelRepo]（无损、零发散，同插画 tab 思路），
 * 过滤走 repo 自己的 Mapper（含搜索 R18 三态 + 仅看 AI），过滤后直接用统一 Novel 建条目。
 *
 * 「系列作品归纳」（issue #1016）开启时整条列表改走网页 ajax（`gs=1`），见
 * [SearchNovelSeriesWebSource]；此时列表里会混进 [SearchNovelSeriesFeedItem] 系列卡。
 *
 * 响应式重搜：observe nowGo → 命中小说标签匹配档才 refresh（对齐 legacy TAG_MATCH_VALUE_NOVEL guard）。
 */
class SearchNovelFeedFragment : NovelFeedFragment() {

    private var searchRefreshPending = false

    private val searchModel: SearchModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(requireActivity())[SearchModel::class.java]
    }

    /** 系列卡的封面 / 头像；与基类小说卡同一个 RequestManager（Glide.with(Fragment) 解析结果一致）。 */
    private val seriesGlide: RequestManager by lazy { Glide.with(this) }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        val searchModel = ViewModelProvider(requireActivity())[SearchModel::class.java]
        val services = requireContext().appServices()
        SearchNovelFeedSource(searchModel) {
            SearchNovelRepo(
                null, null, null, null, null, null, null, null,
                nana7miOutbox = services.accountOnlineReportOutbox,
                nana7miTelemetryService = services.nana7miSearchTelemetry,
                remoteAppConfig = services.remoteAppConfig,
            )
        }
    }

    /**
     * 归纳模式下数据来自 www.pixiv.net：匿名视角看不到 R-18，空结果多半是没登录网页而不是真没搜到。
     * 引导与 [ceui.pixiv.ui.user.UserNovelByTagFeedFragment] 同一套（那页同因同解）。
     */
    override val emptyStateText: CharSequence
        get() {
            SearchRiskPolicy.withheldQuery(searchModel.keyword.value)?.let { query ->
                return getString(R.string.search_results_withheld_notice, query)
            }
            return if (!isGroupBySeries() || SessionManager.hasWebCookie) {
                super.emptyStateText
            } else {
                getString(R.string.search_novel_group_by_series_empty_need_web_login)
            }
        }

    override val emptyStateAction: Pair<CharSequence, () -> Unit>?
        get() {
            if (SearchRiskPolicy.shouldWithhold(searchModel.keyword.value)) return null
            return if (!isGroupBySeries() || SessionManager.hasWebCookie) {
                null
            } else {
                getString(R.string.street_web_login_confirm) to {
                    startActivity(
                        Intent(requireContext(), TemplateActivity::class.java).apply {
                            putExtra(TemplateActivity.EXTRA_FRAGMENT, "Web首页")
                            putExtra(Params.AUTO_WEB_LOGIN, true)
                        }
                    )
                }
            }
        }

    private fun isGroupBySeries(): Boolean = searchModel.groupBySeries.value == true

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return super.onCreateRenderers() + novelSeriesCardRenderer()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 撞热度排序额度时结果会静默降级成预览，这条提示是用户唯一能知道原因的地方。
        observeNana7miQuotaNotice()
        searchModel.nowGo.observe(viewLifecycleOwner) {
            // 命中本地策略时不受普通搜索类型限制：小说页也要清空旧结果。
            val shouldWithhold = SearchRiskPolicy.shouldWithhold(searchModel.keyword.value)
            if (shouldWithhold) {
                // 只走本地空页，离屏执行也不会产生请求；先于横滑可见阶段清掉旧数据。
                searchRefreshPending = false
                feedViewModel.refresh()
            } else if (PixivSearchParamUtil.TAG_MATCH_VALUE_NOVEL.contains(searchModel.searchType.value)) {
                // 离屏 ViewPager 页处于 STARTED 也会收到 LiveData；不在后台搜索，
                // 只记一次待刷新，等这个 tab 真正 RESUMED 时再执行。
                if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    searchRefreshPending = false
                    feedViewModel.refresh()
                } else {
                    searchRefreshPending = true
                }
            } else {
                searchRefreshPending = false
            }
        }
    }

    override fun onResume() {
        val hadExistingLoad = feedViewModel.uiState.value.let {
            it.hasLoadedOnce || it.refresh is LoadState.Loading
        }
        super.onResume()
        if (searchRefreshPending) {
            searchRefreshPending = false
            // 首次进入由 FeedFragment.ensureLoaded() 加载最新条件；只有旧数据或
            // 旧请求存在时才显式刷新，避免首次进入连续发两次。
            if (hadExistingLoad) {
                feedViewModel.refresh()
            }
        }
    }

    /**
     * 归纳模式下的「系列」卡。刻意复用主力小说卡布局 [RecyNovelBinding]：同一屏里系列与单篇
     * 混排，长得不一样反而更难扫读。差异只有三处——
     *   - 系列那行文本改成「全 N 话 / 已完结」（单篇卡那行是「系列: xxx」）；
     *   - 爱心隐藏：系列的官方动作是「加入关注列表(watchlist)」而不是收藏，别摆一个点了没用的心；
     *   - 整卡 / 封面点击进小说系列页，而不是小说详情。
     */
    private fun novelSeriesCardRenderer() =
        feedRenderer<SearchNovelSeriesFeedItem, RecyNovelBinding>(
            inflate = RecyNovelBinding::inflate,
            create = { cell ->
                cell.binding.like.isVisible = false
                cell.binding.root.setOnClick {
                    cell.itemOrNull?.let { openNovelSeriesPage(it.seriesId) }
                }
                cell.binding.cover.setOnClick {
                    cell.itemOrNull?.let { openNovelSeriesPage(it.seriesId) }
                }
                cell.binding.userHead.setOnClick { cell.itemOrNull?.let { openSeriesAuthor(it.novel) } }
                cell.binding.author.setOnClick { cell.itemOrNull?.let { openSeriesAuthor(it.novel) } }
            },
            recycle = { cell ->
                seriesGlide.clear(cell.binding.cover)
                seriesGlide.clear(cell.binding.userHead)
            },
        ) { cell -> bindNovelSeriesCard(cell) }

    private fun bindNovelSeriesCard(cell: FeedCell<SearchNovelSeriesFeedItem, RecyNovelBinding>) {
        val b = cell.binding
        val item = cell.item
        val novel = item.novel
        val ctx = b.root.context
        val palette = V3Palette.from(ctx)

        seriesGlide.load(GlideUtil.getUrl(novel.resolvedCoverUrl()))
            .override(80.ppppx, 119.ppppx)
            .placeholder(R.color.v3_surface_2)
            .error(R.color.v3_surface_2)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(b.cover)

        b.title.text = novel.title ?: ""
        b.series.isVisible = true
        b.series.setTextColor(palette.textAccent)
        b.series.text = ctx.getString(
            if (item.isConcluded) R.string.search_novel_series_card_concluded
            else R.string.search_novel_series_card_ongoing,
            item.episodeCount,
        )
        b.author.text = novel.user?.name ?: ""
        b.date.text = novel.create_date?.take(10) ?: ""
        b.bookmarkCount.text = (novel.total_bookmarks ?: 0).toString()
        b.howManyWord.text = ctx.getString(
            R.string.v3_novel_word_count,
            (novel.text_length ?: 0).toString(),
        )
        b.badgeAi.isVisible = novel.novel_ai_type == 2

        val tags = if (Shaft.sSettings.isShowNovelCardTags()) novel.tags.orEmpty() else emptyList()
        b.novelTag.compact = true
        b.novelTag.searchIndex = 1
        b.novelTag.showHashPrefix = false
        b.novelTag.showTranslation = false
        b.novelTag.maxTags = if (Shaft.sSettings.isCollapseNovelCardTags()) 6 else -1
        b.novelTag.setTags(tags)
        b.novelTag.isVisible = tags.isNotEmpty()

        novel.user?.let { seriesGlide.load(GlideUtil.getHead(it)).into(b.userHead) }
    }

    private fun openNovelSeriesPage(seriesId: Long) {
        startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(NovelSeriesFragment.ARG_SERIES_ID, seriesId)
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说系列")
        })
    }

    private fun openSeriesAuthor(novel: Novel) {
        novel.user?.id?.takeIf { it > 0L }?.let { openUserActivity(it) }
    }

    companion object {
        @JvmStatic
        fun newInstance(): SearchNovelFeedFragment = SearchNovelFeedFragment()
    }
}

/**
 * 搜索小说数据源。两条路二选一，**在 load(null) 那一刻定死**，整代翻页都走同一条：
 *   - 「系列作品归纳」关（默认）：包裹 [SearchNovelRepo]（同插画 tab），游标 = next_url；
 *   - 开：走 [SearchNovelSeriesWebSource]（网页 `gs=1`），游标 = 页码。
 *
 * 两条路的游标格式不同，所以不能每页现读开关——那会让上一代的 next_url 落到页码那条路上。
 * 用户切开关必定触发一次搜索（sheet 提交 → nowGo → refresh），refresh 从 load(null) 起，
 * 模式自然跟着换。
 */
class SearchNovelFeedSource(
    private val searchModel: SearchModel,
    /** 只在首页真正要发请求时才调；风险拦截命中的首页不会建 Repo。 */
    private val repoFactory: () -> SearchNovelRepo,
) : FeedSource<String> {

    private var repo: SearchNovelRepo? = null
    private val webSource by lazy(LazyThreadSafetyMode.NONE) {
        SearchNovelSeriesWebSource(searchModel)
    }
    /** 本代用的是不是归纳模式；null = 还没加载过第一页。 */
    private var groupedGeneration: Boolean? = null

    override suspend fun load(cursor: String?): FeedPage<String> {
        // 首页固定本代 keyword；翻页沿用本代 repo / 网页参数，不受尚未提交的输入草稿影响。
        val keywordSnapshot = if (cursor == null) searchModel.keyword.value.orEmpty() else null
        if (keywordSnapshot != null) {
            val shouldWithhold = if (SearchRiskPolicy.isWarmedUp()) {
                SearchRiskPolicy.shouldWithhold(keywordSnapshot)
            } else {
                withContext(Dispatchers.Default) {
                    SearchRiskPolicy.shouldWithhold(keywordSnapshot)
                }
            }
            if (shouldWithhold) {
                return FeedPage(emptyList(), null)
            }
        }
        val grouped = if (cursor == null) {
            (searchModel.groupBySeries.value == true).also { groupedGeneration = it }
        } else {
            groupedGeneration ?: (searchModel.groupBySeries.value == true)
        }
        if (grouped) {
            return if (cursor == null) {
                webSource.loadFirstPage(requireNotNull(keywordSnapshot))
            } else {
                webSource.load(cursor)
            }
        }
        val r = repo ?: repoFactory().also { repo = it }
        val list: ListNovel = if (cursor == null) {
            r.update(searchModel, keywordSnapshot)
            r.initApi().awaitFirstValue()
        } else {
            r.setNextUrl(cursor)
            r.initNextApi().awaitFirstValue()
        }
        val items = withContext(Dispatchers.Default) {
            @Suppress("UNCHECKED_CAST")
            val filtered = (r.mapper() as Function<ListNovel, ListNovel>).apply(list)
            // Mapper 已做完搜索专属过滤（R18 三态 / 仅看 AI），不再重复走全局过滤。
            filtered.list.orEmpty().map { NovelFeedItem(it) }
        }
        return FeedPage(items, list.nextUrl?.takeIf { it.isNotEmpty() })
    }

}
