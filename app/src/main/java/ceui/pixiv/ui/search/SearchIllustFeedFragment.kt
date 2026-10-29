package ceui.pixiv.ui.search

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.model.ListIllust
import ceui.lisa.repo.SearchIllustRepo
import ceui.lisa.utils.PixivSearchParamUtil
import ceui.lisa.viewmodel.SearchModel
import ceui.pixiv.api.model.Illust
import ceui.pixiv.services.appServices
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.observeR18FilterChanges
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ceui.pixiv.ui.usage.observeNana7miQuotaNotice

/**
 * 搜索「插画/漫画」tab（feeds 框架版，替代 legacy FragmentSearchIllust + SearchIllustRepo + IAdapter）。
 * 卡片复用 [IllustFeedFragment] 的标准瀑布流插画卡。
 *
 * 搜索链路重（sort 路由 / 内置热门榜 / 投稿期间档 / 关键字后缀 / R18 三态 + 仅看 AI + starSize
 * 客户端过滤）——**为无损、零发散，数据源直接包裹既有的 [SearchIllustRepo]**（复刻它全部逻辑风险太大），
 * 直接调它的 suspend initApi/initNextApi，通用过滤走 repo 自己的 FilterMapper；R-18 三态 / AI / 已收藏
 * 由 [shouldHideSearchIllust] 只打隐藏标记（条件变了能就地放回）。用 [IllustFeedItem.raw] 直接建条目
 *（**绝不能走 .of，会在仅看 AI 时误删 AI**）。
 *
 * 响应式重搜：数据源读 activity-scoped [SearchModel] 最新参数（不快照），fragment observe nowGo →
 * 命中标签匹配档才 refresh（对齐 legacy 的 TAG_MATCH_VALUE guard，防选了小说专属 target 时插画也重搜）。
 */
class SearchIllustFeedFragment : IllustFeedFragment() {

    private var searchRefreshPending = false
    private var syncVisibilityJob: Job? = null

    private val searchModel: SearchModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(requireActivity())[SearchModel::class.java]
    }

    private val loadedSpec: LoadedSearchSpec by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this)[LoadedSearchSpec::class.java]
    }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获：捕获 activity-scoped SearchModel（≥ Activity 生命周期），先取局部 val
        val searchModel = ViewModelProvider(requireActivity())[SearchModel::class.java]
        // 进程级服务也先取局部 val（应用级对象，不延长任何 Fragment 生命周期）
        val services = requireContext().appServices()
        val loadedSpec = loadedSpec
        SearchIllustFeedSource(searchModel, loadedSpec) {
            // 8 个必填参数先给 null，全部由 update(searchModel) 填；构造时 super 已建好 FilterMapper。
            SearchIllustRepo(
                null, null, null, null, null, null, null, null,
                nana7miOutbox = services.accountOnlineReportOutbox,
                nana7miTelemetryService = services.nana7miSearchTelemetry,
                remoteAppConfig = services.remoteAppConfig,
                bookmarkMirror = services.bookmarkMirror,
            )
        }
    }

    override val emptyStateText: CharSequence
        get() = SearchRiskPolicy.withheldQuery(searchModel.keyword.value)?.let { query ->
            getString(R.string.search_results_withheld_notice, query)
        } ?: super.emptyStateText

    /**
     * 不把搜索游标交给详情页 pager 续读（基类默认会交）。
     *
     * 基类那句默认（「pixiv 列表的游标本身就是 nextUrl，详情页划到底可以照着它继续请求」）隐含
     * 一个前提：**nextUrl 拉回来的东西就是本列表的结果集**。搜索不满足——搜索的结果集是由
     * [ceui.lisa.repo.SearchIllustRepo] 的 FilterMapper（收藏数门槛）加 [shouldHideSearchIllust]
     *（R-18 三态 / 仅看 AI）共同定义的，nextUrl 只是那条流水线的入料。
     *
     * 而详情 pager 的回传链复现不了这条流水线：VActivity 用的是裸 `Mapper`（不认 searchR18Restriction
     * / searchOnlyAi），回到本页 `feedItemFromBean` 默认走 [IllustFeedItem.of] →
     * `passesContentFilters`（只有全局过滤链）。两头都丢，于是：
     * - R-18 限制选「仅安全」→ 续拉页整页 R-18 全部放行，追回列表；
     * - 「仅看 AI」+ 全局「屏蔽 AI 作品」开 → 首屏靠 [shouldHideSearchIllust] 在仅看 AI 时不判屏蔽保住 AI，
     *   续拉页没有这个让步，`passesContentFilters` 把 AI 全删干净 —— 与用户诉求正好相反。
     *
     * 交 null 即关掉续读：详情页仍可在交接来的快照里翻，只是划到底不再自动续拉（对齐所有本地源
     * 的既有做法）。要恢复续读，得先让回传链拿得到搜索档位、能一比一复现上面这条流水线，
     * 而不是把这个游标交出去。[IllustFeedItem.raw] 的文档已经写明「搜索专属过滤 feeds 侧
     * 不复刻」——本页正是那条禁令的适用对象。
     */
    override val detailContinuationCursor: String?
        get() = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 撞热度排序额度时结果会静默降级成预览，这条提示是用户唯一能知道原因的地方。
        observeNana7miQuotaNotice()
        viewLifecycleOwner.observeR18FilterChanges(requireContext(), ::syncItemVisibility)
        searchModel.nowGo.observe(viewLifecycleOwner) {
            // 普通查询仍只在「标签匹配」档响应（对齐 legacy）。命中本地策略时则所有
            // 分栏都必须刷新为空，避免切换 tab 后短暂看到上一次搜索留下的结果。
            val shouldWithhold = SearchRiskPolicy.shouldWithhold(searchModel.keyword.value)
            if (shouldWithhold) {
                // 本地短路不会发网络；离屏页也立即清空，避免 ViewPager 滑动过程中在
                // onResume 之前露出上一代结果。
                searchRefreshPending = false
                feedViewModel.refresh()
            } else if (PixivSearchParamUtil.TAG_MATCH_VALUE.contains(searchModel.searchType.value)) {
                val isResumed = viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if (canSyncInPlace()) {
                    // 网络 URL 未变：就地上下屏，不发请求、不丢位置
                    searchRefreshPending = false
                    if (isResumed) {
                        deferSyncItemVisibilityWithSpinner()
                    } else {
                        syncItemVisibility()
                    }
                } else if (isResumed) {
                    searchRefreshPending = false
                    feedViewModel.refresh()
                } else {
                    searchRefreshPending = true
                }
            } else {
                // A later event may target only the novel tab. Do not carry an older pending
                // illustration refresh across that newer, incompatible SearchModel snapshot.
                searchRefreshPending = false
            }
        }
    }

    /**
     * 当前条件的网络入参与屏上这一代结果相同 → 只需就地上下屏，不必重新请求。
     *
     * 刷新进行中不算：在飞的请求回来会整代替换，就地同步的只是即将被换掉的旧代
     *（例如改了排序、请求还没回来又改回原样：不重搜的话最后落地的是改动后那一代）。
     */
    private fun canSyncInPlace(): Boolean {
        val state = feedViewModel.uiState.value
        return state.hasLoadedOnce && state.refresh is LoadState.Idle &&
            loadedSpec.spec == buildIllustNetworkSpec(searchModel)
    }

    /**
     * 前台点击搜索条件 sheet 弹窗的搜索后，若网络 URL 未变，
     * 转动假加载环等待 sheet 收起动画结束，再执行就地上下屏。
     */
    private fun deferSyncItemVisibilityWithSpinner() {
        syncVisibilityJob?.cancel()
        syncVisibilityJob = viewLifecycleOwner.lifecycleScope.launch {
            setRefreshSpinnerActive(true)
            try {
                delay(SHEET_DISMISS_DELAY_MS)
                syncItemVisibility()
            } finally {
                setRefreshSpinnerActive(false)
            }
        }
    }

    private fun setRefreshSpinnerActive(active: Boolean) {
        if (view != null) {
            runCatching { feedBinding.feedRefreshLayout.isRefreshing = active }
        }
    }

    override fun onDestroyView() {
        syncVisibilityJob?.cancel()
        syncVisibilityJob = null
        super.onDestroyView()
    }

    /**
     * 就地重算屏上每条插画的显隐（过滤已收藏 / R-18 限制），不发网络请求、保留滚动锚点。
     */
    fun syncItemVisibility(): Boolean {
        val bookmarkFilter = searchModel.sessionBookmarkFilter.value
            ?: Shaft.sSettings.isSearchFilterBookmarked
        val r18Restriction = searchModel.r18Restriction.value ?: 0
        val globalR18Filter = Shaft.sSettings.isR18FilterTempEnable
        val onlyAi = searchModel.onlyAi.value == true
        val excludeAi = searchModel.sessionExcludeAi.value
            ?: (runCatching { Shaft.sSettings?.isDeleteAIIllust }.getOrNull() ?: false)
        return applyItemVisibility { item ->
            val illustItem = item as? IllustFeedItem ?: return@applyItemVisibility item
            val wantHidden = shouldHideSearchIllust(
                illust = illustItem.illust,
                bookmarkFilter = bookmarkFilter,
                r18Restriction = r18Restriction,
                globalR18FilterOn = globalR18Filter,
                onlyAi = onlyAi,
                excludeAi = excludeAi,
            )
            if (illustItem.hidden == wantHidden) item else illustItem.withHidden(wantHidden)
        }
    }

    /**
     * 当前结果的头几张作品，给置顶卡片当预览（[SearchPinController]）。只取已经显示在屏上的
     * 这一代结果；还没加载过就是空，置顶照样成立，只是卡片不带图。
     */
    fun previewIllusts(limit: Int): List<Illust> =
        feedViewModel.uiState.value.visibleItems.asSequence()
            .filterIsInstance<IllustFeedItem>()
            .map { it.illust }
            .take(limit)
            .toList()

    override fun onResume() {
        // 先快照切换前的加载状态：super.onResume() 会为从未加载的页面执行
        // ensureLoaded()，这种情况已经会使用最新 SearchModel，不能紧接着再 refresh 一次。
        // 旧页面已加载或旧请求还在跑时，则需要显式 refresh 替换成最新条件。
        val hadExistingLoad = feedViewModel.uiState.value.let {
            it.hasLoadedOnce || it.refresh is LoadState.Loading
        }
        super.onResume()
        if (searchRefreshPending) {
            searchRefreshPending = false
            if (hadExistingLoad) {
                if (canSyncInPlace()) {
                    syncItemVisibility()
                } else {
                    feedViewModel.refresh()
                }
            }
        }
    }

    companion object {
        private const val SHEET_DISMISS_DELAY_MS = 350L

        @JvmStatic
        fun newInstance(): SearchIllustFeedFragment = SearchIllustFeedFragment()
    }
}

/**
 * 搜索插画数据源：包裹 [SearchIllustRepo]。load(null) 前 `update(searchModel)` 重读最新参数 +
 * 配置 FilterMapper（starSize）；load(cursor) 用 repo 翻页。通用过滤走 repo.mapper()
 * （FilterMapper，屏蔽 tag/用户 + ObjectPool 合池，setValue 失败自动 postValue 兜底，off-main 安全），
 * 之后按当前 R-18 三态 / AI / 已收藏档位给每条打隐藏标记（[shouldHideSearchIllust]），不丢条目。
 */
class SearchIllustFeedSource(
    private val searchModel: SearchModel,
    /** 首页成功落地后记下本代网络入参，供 Fragment 判断条件变更要不要重新请求。 */
    private val loadedSpec: LoadedSearchSpec,
    /** 只在首页真正要发请求时才调；风险拦截命中的首页不会建 Repo。 */
    private val repoFactory: () -> SearchIllustRepo,
) : FeedSource<String> {

    private var repo: SearchIllustRepo? = null

    override suspend fun load(cursor: String?): FeedPage<String> {
        // 策略只决定一代搜索的首页；翻页沿用这一代已经固定的 nextUrl。每次键入会更新
        // SearchModel，但未提交前不应截断屏幕上那一代安全结果的翻页。
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
                loadedSpec.spec = buildIllustNetworkSpec(searchModel, keywordSnapshot)
                return FeedPage(emptyList(), null)
            }
        }
        val r = repo ?: repoFactory().also { repo = it }
        // initApi / initNextApi 是 suspend：借号、缓存查询、Pixiv 请求全在各自的挂起点里切线程，
        // 这里不用再包 withContext(IO)。（搜索历史写入已上移到 SearchActivity，update 不做 Room I/O。）
        val list: ListIllust = if (cursor == null) {
            r.update(searchModel, keywordSnapshot) // 与上面的策略判断共用同一 keyword 快照
            // 入参必须与 update 同一刻快照：请求挂起期间 SearchModel 会随键入 / 条件面板实时变，
            // 回来再读会把没发出去的条件记成「已加载」，下次提交被误判为 URL 未变而不重搜。
            val spec = buildIllustNetworkSpec(searchModel, requireNotNull(keywordSnapshot))
            r.initApi().also { loadedSpec.spec = spec }
        } else {
            r.nextUrl = cursor
            r.initNextApi()
        }
        val items = withContext(Dispatchers.Default) {
            @Suppress("UNCHECKED_CAST")
            val filtered = r.mapper().apply(list)
            // FilterMapper 已做完搜索通用过滤。R-18 限制与过滤已收藏改为 feeds 侧「只打标记、不丢条目」
            // （支持零请求就地上下屏，不破坏关掉开关原地放回的能力）。
            val bookmarkFilter = searchModel.sessionBookmarkFilter.value
                ?: Shaft.sSettings.isSearchFilterBookmarked
            val r18Restriction = searchModel.r18Restriction.value ?: 0
            val globalR18Filter = Shaft.sSettings.isR18FilterTempEnable
            val onlyAi = searchModel.onlyAi.value == true
            val excludeAi = searchModel.sessionExcludeAi.value
                ?: (runCatching { Shaft.sSettings?.isDeleteAIIllust }.getOrNull() ?: false)
            filtered.list.orEmpty().mapNotNull { illust ->
                val item = IllustFeedItem.raw(illust) ?: return@mapNotNull null
                val isHidden = shouldHideSearchIllust(
                    illust = illust,
                    bookmarkFilter = bookmarkFilter,
                    r18Restriction = r18Restriction,
                    globalR18FilterOn = globalR18Filter,
                    onlyAi = onlyAi,
                    excludeAi = excludeAi,
                )
                item.withHidden(isHidden)
            }
        }
        return FeedPage(items, list.nextUrl?.takeIf { it.isNotEmpty() })
    }
}
