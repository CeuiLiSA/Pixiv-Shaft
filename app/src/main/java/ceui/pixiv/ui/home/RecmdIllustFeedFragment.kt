package ceui.pixiv.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import androidx.annotation.LayoutRes
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.ColdStartSplashHost
import ceui.lisa.activities.RankActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.adapters.RAdapter
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.databinding.RecyRecmdHeaderBinding
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.model.ListIllust
import ceui.pixiv.api.model.Illust
import ceui.lisa.utils.Common
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.Params
import retrofit2.HttpException
import ceui.lisa.view.LinearItemHorizontalDecoration
import ceui.lisa.view.SpacesItemWithHeadDecoration
import ceui.pixiv.api.Client
import ceui.pixiv.db.discovery.DiscoveryPool
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedLoadPhase
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.pixiv.cachedPixivFeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.pixiv.ui.common.JustifiedItemDecoration
import ceui.pixiv.ui.common.JustifiedLayoutManager
import ceui.pixiv.ui.common.scrollToPositionWithOffset
import ceui.pixiv.ui.common.showCardMenu
import ceui.pixiv.ui.common.staggerIllustRenderer
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.discovery.WebDiscoverySession
import ceui.pixiv.utils.isFullDetail
import ceui.pixiv.utils.setOnClick
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ceui.pixiv.services.appServices
import ceui.pixiv.session.SessionManager
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 首页「推荐插画」tab / 推荐漫画页（feeds 框架版，替代 legacy FragmentRecmdIllust +
 * IAdapterWithHeadView + RecmdModel）。异构列表 = 横向排行榜预览头（整行）+ 插画瀑布流。
 *
 * 与 legacy 的行为对齐点：
 * - 首屏响应的 ranking_illusts 渲染横向排行榜预览（RAdapter hero 卡），
 *   「查看更多」进 RankActivity，卡片点击开 VActivity（一次性 PageData，同 legacy IllustHeader），
 *   长按弹和瀑布流卡同一套菜单（见 [bindRankStrip]）；
 * - 排行榜预览的 bean 同样合入 ObjectPool + 灌关注状态（poolableBeansOf 覆盖）；
 * - 每页数据过滤前整页喂 DiscoveryPool（对齐 RecmdIllustRepo）；
 * - 「收藏时显示相关作品」：收藏成功后 FRAGMENT_ADD_RELATED_DATA 回流，
 *   截前 5 条打 NEW 角标插到被收藏作品后面（feeds 版按作品 id 锚定 + 身份去重，
 *   替代 legacy 不可靠的 adapter 位置语义）；
 * - SpacesItemWithHeadDecoration（带头瀑布流的间距规则；LayoutManager 随「插画列表布局」走基类）。
 */
open class RecmdIllustFeedFragment(
    @LayoutRes contentLayoutId: Int = ceui.pixiv.feeds.R.layout.fragment_feed,
) : IllustFeedFragment(contentLayoutId) {

    /**
     * 铺到屏幕底,底部安全区靠 inset 补:无底栏的宿主是手势条/导航栏,首页那份是浮在内容之上的
     * 底栏高度(MainActivity 重写了分发给内容区的 inset)。以前首页是 view_pager 让位给底栏,
     * 这里保持 false;现在底栏会跟随滚动收起,内容必须铺满整屏,改由列表自己补 padding。
     */
    override val applyBottomSafeInset: Boolean = true

    protected val dataType: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_DATA_TYPE) ?: TYPE_ILLUST
    }

    private val r18ViewModel: RecmdR18ViewModel by viewModels()

    /**
     * 最近一次应用到列表上的开关值；null = 本实例还没同步过（首次只记录、不动列表）。
     * 它不需要活过旋转：真正的「谁被藏起来了」记在条目自己的 [IllustFeedItem.hidden] 上，
     * 重建后列表还是那份列表，天然一致。
     */
    private var appliedBookmarkFilter: Boolean? = null

    /** 上 / 下屏前记下的滚动锚点，等这次提交落地后按它把位置接回来（见 [onListCommitted]）。 */
    private var pendingBookmarkAnchor: BookmarkScrollAnchor? = null

    /** [onListReady] 给的列表引用；视图销毁后置空，锚点捕获只认活着的列表。 */
    private var listViewRef: RecyclerView? = null

    override val feedViewModel by feedViewModels {
        // 零捕获约定（见 feedViewModels 文档）：source/mapper 归 VM 长期持有，
        // 只捕获局部值、映射走伴生函数，不把 Fragment 实例钉进 VM
        val dataType = dataType
        val apiType = if (dataType == TYPE_MANGA) "manga" else "illust"
        // 应用级对象，捕获它不会把 Fragment 钉进 VM
        val pool = requireContext().appServices().discoveryPool
        // 缓冲池归 RecmdR18ViewModel 长期持有，由后台协程异步注水，首屏与后续翻页按固定步长均匀平摊混入。
        // 同为 Fragment 作用域的 VM，与 feedViewModel 同生命周期，捕获它不违反零捕获约定。
        val r18Vm = r18ViewModel
        val r18Buffer = r18Vm.buffer
        // 本地优先（哔哩哔哩 / 新闻首页语义）：给稳定 slot 即开磁盘缓存，冷启秒显上次首屏
        // 再拉最新覆盖。slot 已由框架自动拼账号命名空间，切号不串味。
        cachedPixivFeedSource(
            slot = "recmd-$apiType",
            // 首屏拉取与网页 R-18 推荐拉取并行出发（两相奔赴、各自完成）：
            // 立即在后台触发 r18ViewModel.recharge 拉取 R-18，同时发起 App API 全年龄推荐请求（耗时约 200ms）。
            // 绝不挂起等待慢速的网页 R-18 接口；拉回后注满缓冲池供翻页消耗，首屏补偿由 Fragment
            // 观察 [RecmdR18ViewModel.loadedGeneration] 触发（不能在这里传 Fragment 回调进 VM）。
            initialFetch = {
                r18Vm.recharge(apiType)
                Client.appApi.getRecommendedWorksWithRanking(apiType)
            },
            // 「启动时自动刷新首页推荐」(issue #955)：关掉后冷启命中快照就停在快照上，
            // 由用户下拉刷新才换一批——推荐流每次冷启整代替换，会让上次没翻完的作品直接消失。
            // 只作用于首页那份实例：推荐漫画是从别处点进去的独立页面，不属于「启动」语义。
            // lambda 每次刷新现读设置（不用重建数据源），效果本就只在下次冷启看得到。
            refreshAfterCacheHit = {
                dataType != TYPE_ILLUST || Shaft.sSettings.isAutoRefreshHomeFeed
            },
        ) { resp, phase ->
            val illusts = if (phase == FeedLoadPhase.NextPage && isR18RecommendEligible()) {
                val batch = r18Buffer.takeBatch(
                    resp.illusts.size / R18_INTERLEAVE_STEP,
                    excludedIds = resp.illusts.mapTo(HashSet()) { it.id },
                )
                val merged = interleaveIllusts(resp.illusts, batch, step = R18_INTERLEAVE_STEP)
                Timber.d(
                    "RecmdIllustFeedFragment[NextPage]: normal=%d, r18Batch=%d, remainingBuffer=%d, merged=%d",
                    resp.illusts.size,
                    batch.size,
                    r18Buffer.size,
                    merged.size,
                )
                merged
            } else {
                resp.illusts
            }
            mapRecmdPage(pool, illusts, resp.ranking_illusts, phase, dataType)
        }
    }

    /** 收藏时顺带拉相关作品插入列表（本页专属设置）。 */
    override val showRelatedOnStar: Boolean
        get() = Shaft.sSettings.isShowRelatedWhenStar

    /**
     * 排行榜预览头携带的 bean 也要合池 + 灌关注状态（对齐 legacy onFirstLoaded）。
     * 官网 R-18 推荐是精简缩略图（没有关注态），不能拿去覆盖池里的完整 bean。
     */
    override fun poolableBeansOf(item: FeedItem): List<Illust> {
        return if (item is RankPreviewHeaderItem) {
            item.rankBeans
        } else {
            super.poolableBeansOf(item).filter { it.isFullDetail() }
        }
    }

    /** 收藏成功回流的相关作品：按被收藏作品 id 锚定，截前 5 条打 NEW 角标插到它后面。 */
    private val relatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val extras = intent?.extras ?: return
            // 只认本列表发起的收藏；legacy 发送方不带 uuid，退回宽松的按 id 锚定兜底
            val sourceUuid = extras.getString(Params.PAGE_UUID)
            if (sourceUuid != null && sourceUuid != syncViewModel.listPageUuid) return
            val listIllust = extras.getSerializable(Params.CONTENT) as? ListIllust ?: return
            val anchorId = extras.getInt(Params.ID).toLong()
            if (anchorId <= 0) return
            viewLifecycleOwner.lifecycleScope.launch {
                // 条目过滤含同步 Room 查询，不占主线程
                val related = withContext(Dispatchers.Default) {
                    listIllust.list.orEmpty().take(5)
                        .map { it.withRelated(true) }
                        .mapNotNull { feedItemFromBean(it) }
                }
                if (related.isEmpty()) return@launch
                feedViewModel.mutateItems { items ->
                    val anchor =
                        items.indexOfFirst { it is IllustFeedItem && it.illust.id == anchorId }
                    if (anchor < 0) return@mutateItems items
                    val existing =
                        items.mapNotNullTo(HashSet()) { (it as? IllustFeedItem)?.illust?.id }
                    val fresh = related.filter { it.illust.id !in existing }
                    if (fresh.isEmpty()) {
                        items
                    } else {
                        items.subList(0, anchor + 1) + fresh + items.subList(anchor + 1, items.size)
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(relatedReceiver, IntentFilter(Params.FRAGMENT_ADD_RELATED_DATA))
        // 本类在两处被复用：FragmentLeft（首页推荐 tab，TYPE_ILLUST）和独立的
        // RecmdMangaFeedFragment（TemplateActivity 里的「推荐漫画」页，TYPE_MANGA）——
        // 后者跟 MainActivity 冷启动无关，只有 TYPE_ILLUST 这份实例的裁决才代表
        // MainActivity.getNavigationInitPosition()==0 时开屏该不该放行。
        // 只等 refresh 走完首个决定（refresh 不再是初始 Idle，或已经 hasLoadedOnce）：
        // 即命中缓存 / 未命中都算数，不等网络，避免开屏被无界的网络延迟焊死。
        if (dataType == TYPE_ILLUST) {
            viewLifecycleOwner.lifecycleScope.launch {
                feedViewModel.uiState.first { it.refresh !is LoadState.Idle || it.hasLoadedOnce }
                (activity as? ColdStartSplashHost)?.markSplashResolved()
                // 开屏放行时网络刷新多半还在路上（顶部刷新圈在转）：等它收尾再让宿主放行其它 tab 页，
                // 否则那些页的建视图 + 各自拉数据上屏会和首页这次刷新挤在一起，刷新圈明显卡顿。
                feedViewModel.uiState.first { it.refresh !is LoadState.Loading }
                (activity as? ColdStartSplashHost)?.markColdStartRefreshSettled()
            }
        }

        // R-18 拉取由 initialFetch 随每次网络刷新并行触发；缓冲池注满后尝试首屏动态补偿混入。
        // 重建后重新订阅会拿到当前代数再补偿一次，compensateFirstPage 已有 R-18 时自行跳过。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                r18ViewModel.loadedGeneration
                    .filter { it > 0 }
                    .collectLatest { compensateFirstPage() }
            }
        }
    }

    /**
     * 当后台异步拉取完 R-18 推荐后，若当前展示的首屏尚无 R-18 内容，
     * 从缓冲池提取一批按步长混入当前首屏，使用 mutateItems 平滑更新。
     *
     * 挂起在调用方（STARTED 内的 collectLatest）里跑：离开前台或新一代数据到达即取消等待，
     * 不会在 view 作用域里堆积多个同时放行、各自从缓冲池取一批的补偿协程。
     */
    private suspend fun compensateFirstPage() {
        if (!isR18RecommendEligible()) return
        // 若 Web R-18 比 App API 先返回，等待真实网络首屏到达
        val targetState = feedViewModel.uiState.first {
            !it.itemsFromCache && it.refresh is LoadState.Idle && it.hasLoadedOnce && it.items.isNotEmpty()
        }
        val currentItems = targetState.items
        // 已经存在 R-18 条目（例如翻页已经混入过）则无需补偿
        val alreadyHasR18 = currentItems.any {
            (it as? IllustFeedItem)?.illust?.x_restrict?.let { x -> x > 0 } == true
        }
        if (alreadyHasR18) return

        val normalIllustCount = currentItems.count { it is IllustFeedItem }
        val targetR18Count = (normalIllustCount / R18_INTERLEAVE_STEP).coerceIn(1, 10)
        val existingIds = currentItems.mapNotNullTo(HashSet()) { (it as? IllustFeedItem)?.illust?.id }
        val batch = r18ViewModel.buffer.takeBatch(targetR18Count, excludedIds = existingIds)
        if (batch.isEmpty()) return

        val feedItems = withContext(Dispatchers.Default) {
            batch.mapNotNull { IllustFeedItem.of(it) }
        }
        if (feedItems.isEmpty()) return

        feedViewModel.mutateItems { items ->
            val stillHasR18 = items.any {
                (it as? IllustFeedItem)?.illust?.x_restrict?.let { x -> x > 0 } == true
            }
            if (stillHasR18) {
                items
            } else {
                interleaveFeedItems(items, feedItems, step = R18_INTERLEAVE_STEP)
            }
        }
        Timber.d(
            "compensateFirstPage: successfully interleaved %d r18 items into first page (total now: %d)",
            feedItems.size,
            feedViewModel.uiState.value.items.size,
        )
    }

    override fun onDestroyView() {
        listViewRef = null
        // 视图没了，这次提交也不会再来：锚点留着只会在重建后的第一次提交上莫名滚一下
        pendingBookmarkAnchor = null
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(relatedReceiver)
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        syncBookmarkFilter()
    }

    /**
     * 把「首页推荐过滤已收藏」的开关状态同步到屏幕上的内容 —— 只在开关值真的变了时动列表，
     * 不重新拉数据。
     *
     * 条目本身不摘走，只翻 [IllustFeedItem.hidden]：它仍然留在 [FeedUiState.items] 里，位置和身份
     * 都不变，只是不参与渲染（见 [FeedUiState.visibleItems]）。所以「关掉开关把条目放回来」不需要
     * 任何暂存表，翻回标记就原地出现。
     *
     * 挂在 [onResume]：设置页是另一个页面，改完开关回到首页正好触发一次 resume（与底栏 / 顶栏
     * 那些设置的生效时机同一套）。
     *
     * 首次同步（[appliedBookmarkFilter] 为 null）只记录、不动列表：条目在 mapper 里建的时候已经
     * 按当时的设置打好标记（缓存恢复也会重跑 mapper），旋转重建后同样如此。顺带保证「刚收藏的作品」
     * 不会因为一次 resume 就被重新藏起来。
     */
    private fun syncBookmarkFilter() {
        if (dataType != TYPE_ILLUST) return
        val target = Shaft.sSettings.isFilterRecmdBookmarked
        if (appliedBookmarkFilter == target) return
        val firstSync = appliedBookmarkFilter == null
        appliedBookmarkFilter = target
        if (firstSync) return
        // 锚点取「这次变更后仍然可见」的条目：打开过滤时若锚到正好是已收藏的那张，藏起来后就没得对了
        val anchor = captureScrollAnchor { item -> !target || !isBookmarked(item) }
        val changed = applyBookmarkFilter(target)
        pendingBookmarkAnchor = when {
            !changed -> null
            // 放回来：内容会整体下移，按锚点把位置按住
            !target -> anchor
            // 藏起来：锚点还压在视口顶部（offset <= 0）说明被藏的都在这张卡上方，按住它位置不动；
            // 否则被藏的正是顶部那张，让下面的内容自然顶上来，硬按回原位只会在顶部留一块空白
            anchor != null && anchor.offset <= 0 -> anchor
            else -> null
        }
    }

    private fun isBookmarked(item: FeedItem): Boolean =
        (item as? IllustFeedItem)?.illust?.isBookmarked == true

    /**
     * 按 [hide] 重刷每条插画的隐藏标记，返回是否真的改了列表。
     *
     * 只在开关变化时调用：收藏一件作品走 [IllustFeedItem.withBookmarked]，不经过这里，
     * 所以「刚收藏的作品立刻从屏幕上消失」不会发生。
     *
     * 这里在 mutateItems 的 edit 里攒改动并顺手记一个 changed 标记，依赖的是它的既有前提：
     * VM 的状态变更全在主线程串行发生，MutableStateFlow.update 不会重放 lambda。
     */
    private fun applyBookmarkFilter(hide: Boolean): Boolean {
        var changed = false
        feedViewModel.mutateItems { items ->
            var mutated: ArrayList<FeedItem>? = null
            items.forEachIndexed { index, item ->
                if (item !is IllustFeedItem) return@forEachIndexed
                val wantHidden = hide && item.illust.isBookmarked
                if (item.hidden == wantHidden) return@forEachIndexed
                (mutated ?: ArrayList(items).also { mutated = it })[index] =
                    item.withHidden(wantHidden)
                changed = true
            }
            mutated ?: items
        }
        return changed
    }

    /**
     * 记下当前最靠上、且 [survives] 的那张卡：作品 id + 卡片顶部相对列表 paddingTop 的高度。
     * 列表还没排版（没有 child）时返回 null，调用方据此跳过位置还原。
     */
    private fun captureScrollAnchor(survives: (FeedItem) -> Boolean): BookmarkScrollAnchor? {
        val list = listViewRef ?: return null
        val items = feedAdapter?.currentList ?: return null
        var bestTop = Int.MAX_VALUE
        var bestId: Long? = null
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i) ?: continue
            val position = list.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val item = items.getOrNull(position) as? IllustFeedItem ?: continue
            if (!survives(item) || child.top >= bestTop) continue
            bestTop = child.top
            bestId = item.illust.id
        }
        val id = bestId ?: return null
        return BookmarkScrollAnchor(
            illustId = id,
            offset = bestTop - list.paddingTop,
            generation = feedViewModel.uiState.value.refreshGeneration,
        )
    }

    /**
     * 摘掉 / 插回条目后把滚动位置接回去：DiffUtil 只保证「可见卡片的内容不变」，上方条目增删
     * 会让整段内容上移 / 下移，所以按上屏前记下的锚点重新对齐一次（偏移口径见
     * [ceui.pixiv.ui.common.scrollToPositionWithOffset] 扩展，齐行 / 瀑布流都认）。
     */
    override fun onListCommitted(state: FeedUiState) {
        super.onListCommitted(state)
        val anchor = pendingBookmarkAnchor ?: return
        pendingBookmarkAnchor = null
        // 换代了就不再按旧锚点跳：那张卡属于上一代，位置没有参考意义
        if (state.refreshGeneration != anchor.generation) return
        val list = listViewRef ?: return
        val adapter = feedAdapter ?: return
        val index = adapter.currentList.indexOfFirst {
            (it as? IllustFeedItem)?.illust?.id == anchor.illustId
        }
        if (index < 0) return
        list.scrollToPositionWithOffset(index, anchor.offset)
    }

    override fun onListReady(listView: RecyclerView) {
        listViewRef = listView
        // 首位是整行榜单条，贴边不留白；齐行布局走自己的分行间距
        val manager = listView.layoutManager
        listView.addItemDecoration(
            if (manager is JustifiedLayoutManager) {
                JustifiedItemDecoration(manager, edgeHeaderAtTop = true)
            } else {
                SpacesItemWithHeadDecoration(DensityUtil.dp2px(8.0f))
            }
        )
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(
            rankHeaderRenderer(),
            staggerIllustRenderer(),
        )
    }

    private fun rankHeaderRenderer() = feedRenderer<RankPreviewHeaderItem, RecyRecmdHeaderBinding>(
        inflate = RecyRecmdHeaderBinding::inflate,
        fullSpan = true,
        create = { cell ->
            cell.binding.seeMore.setOnClick {
                startActivity(Intent(requireContext(), RankActivity::class.java).apply {
                    putExtra("dataType", cell.item.dataType)
                })
            }
            cell.binding.ranking.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            cell.binding.ranking.addItemDecoration(
                LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f))
            )
            cell.binding.ranking.setHasFixedSize(true)
        },
    ) { cell ->
        bindRankStrip(cell)
    }

    /**
     * 榜单预览条的内容绑定。点击开详情，长按弹和瀑布流卡同一套菜单（[showCardMenu]）——
     * 只是「批量操作」「幻灯片」作用在榜单这一份数据上，不是底下那条推荐流。
     *
     * 「屏蔽此作品」在这里的语义是**整张卡消失**而不是打码：横向条走 legacy [RAdapter]，
     * 没有模糊层和粒子层（见 [ceui.lisa.helper.IllustNovelFilter.judgeID] 的注释），
     * 也就没有「点一下取消屏蔽」的落脚点。所以名单在 bind 时现读、屏蔽后当帧重建 adapter，
     * 与 [mapRecmdPage] 下次刷新时的过滤口径（`IllustNovelFilter.judge` 含 judgeID）一致；
     * 反悔走「屏蔽记录」页，同其他画不出遮罩的老列表。
     */
    private fun bindRankStrip(cell: FeedCell<RankPreviewHeaderItem, RecyRecmdHeaderBinding>) {
        val item = cell.itemOrNull ?: return
        val beans = item.rankBeans.filterNot { IllustMuteStore.isMuted(it.id) }
        // 同一份数据重复 bind（滚动回收再回来）不重设 adapter，保留横向滚动位置
        //（对齐 legacy 单例 header 只在数据到达时 show 一次的语义）。
        // 按 id 序列而不是条目本身认「同一份」：屏蔽掉一张后条目没变、可见集合变了，得重建。
        val batchKey = beans.map { it.id }
        if (cell.binding.ranking.tag == batchKey) return
        cell.binding.ranking.tag = batchKey
        val adapter = RAdapter(beans, requireContext())
        adapter.setOnItemClickListener { _, position, _ ->
            // 一次性 PageData（同 legacy IllustHeader）：排行榜预览无 nextUrl，与主列表互不认领
            val pageData = PageData(beans)
            Container.get().addPageToMap(pageData)
            startActivity(Intent(requireContext(), VActivity::class.java).apply {
                putExtra(Params.POSITION, position)
                putExtra(Params.PAGE_UUID, pageData.getUUID())
            })
        }
        adapter.setOnItemLongClickListener { _, position, _ ->
            val bean = beans.getOrNull(position) ?: return@setOnItemLongClickListener
            // raw 不再跑一遍内容过滤：这批 bean 在 mapRecmdPage 里已经滤过了
            val menuItem = IllustFeedItem.raw(bean) ?: return@setOnItemLongClickListener
            showCardMenu(
                menuItem,
                scopedBeans = { beans },
                onToggleSpoiler = { muted ->
                    if (IllustMuteStore.setMuted(bean.id, muted) { bean }) {
                        bindRankStrip(cell)
                    }
                },
            )
        }
        cell.binding.ranking.adapter = adapter
    }

    companion object {
        internal const val ARG_DATA_TYPE = "recmd_data_type"

        /** 官网 R-18 推荐的异步获取上限（在后台静默拉取，不阻塞前台首屏渲染）。 */
        private const val WEB_R18_BUDGET_MS = 12_000L

        /** dataType 是路由字面量（RankActivity 按 "插画"/"漫画" 分支），不是展示文案，别本地化。 */
        const val TYPE_ILLUST = "插画"
        const val TYPE_MANGA = "漫画"

        /**
         * R-18 作品混入步长：在基础全年龄推荐流中，每隔 [R18_INTERLEAVE_STEP] 张全年龄作品
         * 插入 1 张 R-18 作品（R-18 占比 25%）。
         *
         * 在双列瀑布流中，4 张卡片恰好构成 2 行网格，稳定对应 3 张全年龄作品 + 1 张 R-18 作品，
         * 保证分布均匀、不扎堆，同时首屏顶部至少先展示 3 张正常作品。
         */
        const val R18_INTERLEAVE_STEP = 3

        @JvmStatic
        fun isR18RecommendEligible(): Boolean {
            val switchOn = HomeR18Prefs.isEnabled()
            val user = SessionManager.loggedInUser
            val r18Enabled = user?.isR18Enabled() == true
            val xRestrict = user?.x_restrict
            val tempFilter = Shaft.sSettings.isR18FilterTempEnable
            val isCurrentAccount = WebDiscoverySession.isCurrentAccount
            val eligible = switchOn && r18Enabled && !tempFilter && isCurrentAccount
            Timber.d(
                "isR18RecommendEligible: eligible=%s (switch=%s, r18Enabled=%s[x_restrict=%s], tempFilter=%s, isCurrentAccount=%s)",
                eligible,
                switchOn,
                r18Enabled,
                xRestrict,
                tempFilter,
                isCurrentAccount,
            )
            return eligible
        }

        @JvmStatic
        fun newInstance(dataType: String): RecmdIllustFeedFragment {
            return RecmdIllustFeedFragment().apply {
                arguments = Bundle().apply { putString(ARG_DATA_TYPE, dataType) }
            }
        }

        /**
         * 官网首页 mode=r18 的个性化推荐：「为你推荐」在前，「按标签推荐」各行按序接在后面，
         * 跨区块按 id 去重（插画约 18 + 6×24 条，漫画只有约 3 条且没有按标签区块）。
         * 需要与当前账号一致的网页会话；账号在官网关了 R-18 浏览时不请求（会话里的 x_restrict
         * 由冷启动的 me/state 预热和 token 刷新更新）；开着 R18 过滤时也不请求——
         * 拉回来也会被 [IllustFeedItem.of] 滤掉。
         * 超时和任何失败都降级成空列表。若检测到无有效登录态，自动将开关关回并弹出提示。
         */
        internal suspend fun fetchWebR18Recommend(apiType: String): List<Illust> {
            if (!isR18RecommendEligible()) {
                return emptyList()
            }
            Timber.d("fetchWebR18Recommend: start fetching mode=r18 for apiType=%s", apiType)
            return try {
                val resp = withTimeoutOrNull(WEB_R18_BUDGET_MS) {
                    Client.webApi.getTopArtworks(apiType, "r18")
                }
                if (resp == null) {
                    Timber.w("fetchWebR18Recommend: timed out after %d ms", WEB_R18_BUDGET_MS)
                    return emptyList()
                }
                if (resp.error == true) {
                    Timber.w("fetchWebR18Recommend: web response error: %s", resp.message)
                    if (SessionManager.isUnauthenticatedWebResponse(resp)) {
                        handleInvalidWebSession()
                    }
                    return emptyList()
                }
                val body = resp.body
                if (body == null) {
                    return emptyList()
                }
                val page = body.page
                val ids = page?.recommend?.ids.orEmpty() +
                    page?.recommendByTag.orEmpty().flatMap { it.ids.orEmpty() }
                val illusts = body.artworks(ids.distinct())
                    .filter { it.xRestrict > 0 }
                    .map { it.toIllust() }
                Timber.d("fetchWebR18Recommend: fetched %d r18 illusts", illusts.size)
                illusts
            } catch (e: HttpException) {
                if (SessionManager.isUnauthenticatedWebSession(e)) {
                    Timber.w("fetchWebR18Recommend: unauthenticated web session (HTTP %d), disabling switch", e.code())
                    handleInvalidWebSession()
                } else {
                    Timber.w(e, "fetchWebR18Recommend: HTTP error %d", e.code())
                }
                emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "web r18 recommend failed")
                emptyList()
            }
        }

        internal fun isUnauthenticatedWebSession(e: HttpException): Boolean =
            SessionManager.isUnauthenticatedWebSession(e)

        private suspend fun handleInvalidWebSession() {
            HomeR18Prefs.setEnabled(false)
            withContext(Dispatchers.Main) {
                Common.showToast(R.string.web_login_invalid_home_r18_disabled)
            }
        }

        /**
         * 页响应 → 条目。跑在 Default 线程、被 VM 长期持有，放伴生对象保证零捕获。
         *
         * [phase] 为 [FeedLoadPhase.CacheRestore]（缓存恢复）时只做纯映射：不喂画像池——
         * 那是「拉取成功」的副作用，拿旧数据重放会污染下游。映射结构（含排行榜预览头）
         * 与首屏保持一致，靠 [FeedLoadPhase.isFirstPage] 判定。
         */
        private fun mapRecmdPage(
            pool: DiscoveryPool,
            illusts: List<Illust>,
            rankingIllusts: List<Illust>,
            phase: FeedLoadPhase,
            dataType: String,
        ): List<FeedItem> {
            // 对齐 legacy RecmdIllustRepo：过滤前整页喂 DiscoveryPool（排行榜预览不算）。
            // 缓存恢复不喂（旧数据画像无意义、且违反重放安全）；官网精简缩略图也不喂。
            if (phase.isFreshFetch) {
                pool.collect(
                    illusts.filter { it.isFullDetail() },
                    if (phase.isFirstPage) "recmd:$dataType" else "recmd_next:$dataType",
                )
            }
            // 「过滤已收藏」只作用于首页推荐 tab：推荐漫画是从别处点进去的独立页面，不受这个开关管。
            // 官网 Web R-18 推荐条目也走这里：top 接口的 thumbnails 逐条带 bookmarkData（null = 未收藏），
            // 转换后 is_bookmarked 是真实值，所以它们同样会被这一档过滤。
            // 这里只打隐藏标记、不丢条目：关掉开关时它们要原地回来（见 FeedUiState.visibleItems）。
            val hideBookmarked = dataType == TYPE_ILLUST && Shaft.sSettings.isFilterRecmdBookmarked
            val listItems = illusts.mapNotNull { illust ->
                val item = IllustFeedItem.of(illust) ?: return@mapNotNull null
                if (hideBookmarked && illust.isBookmarked) item.withHidden(true) else item
            }
            if (!phase.isFirstPage) {
                return listItems
            }
            // 排行榜预览头也要滤掉屏蔽的作品/标签/画师（issue #543：主列表滤了、这里不滤，
            // 被屏蔽内容就从首页顶部漏出来）；R18/AI 口味过滤照旧不适用——榜单不是个性化推荐
            val rankBeans = rankingIllusts.filterNot { IllustNovelFilter.judge(it) }
            return if (rankBeans.isEmpty()) {
                listItems
            } else {
                listOf(RankPreviewHeaderItem(rankBeans, dataType)) + listItems
            }
        }
    }
}

/**
 * 横向排行榜预览头（整行）。内容相等性按作品 id 序列：刷新拉到同一批榜单时零重绑，
 * 换了批次才重设内部 RAdapter。
 */
class RankPreviewHeaderItem(
    val rankBeans: List<Illust>,
    val dataType: String,
) : FeedItem {

    private val rankIds: List<Long> = rankBeans.map { it.id }

    override val feedKey: Any get() = "recmd_rank_header"

    override fun equals(other: Any?): Boolean {
        return other is RankPreviewHeaderItem && other.rankIds == rankIds
    }

    override fun hashCode(): Int = rankIds.hashCode()
}

/**
 * 官网 R-18 个性化推荐在首页流中的缓冲池。
 *
 * 官网推荐接口（/ajax/top/{illust|manga}?mode=r18）在首屏一次性返回「为你推荐」与「按标签推荐」
 * 全量作品（插画约 160 条，漫画约 3 条）。为了让这些作品在长列表浏览中持续、平稳地与 App 端全年龄
 * 推荐作品交错混排，首屏拉回后存入缓冲池，首屏及后续翻页持续按比例提取。
 *
 * 线程安全：内部使用 [ConcurrentLinkedQueue]，支持跨协程调度安全出队。
 */
class R18RecommendBuffer {

    private val queue = ConcurrentLinkedQueue<Illust>()

    /** 重置缓冲池数据（下拉刷新或新一代首屏拉取成功时调用）。 */
    fun reset(items: List<Illust>) {
        queue.clear()
        if (items.isNotEmpty()) {
            queue.addAll(items)
        }
    }

    /** 清空缓冲池。 */
    fun clear() {
        queue.clear()
    }

    /** 当前剩余作品数量。 */
    val size: Int get() = queue.size

    val isEmpty: Boolean get() = queue.isEmpty()

    /**
     * 批量提取指定数量的 R-18 作品。
     *
     * @param count 期望提取的最大条数。若 <= 0 则直接返回空列表。
     * @param excludedIds 需排除的作品 ID 集合（避免与当前页已有的全年龄推荐作品重复）。
     */
    fun takeBatch(count: Int, excludedIds: Set<Long> = emptySet()): List<Illust> {
        if (count <= 0 || queue.isEmpty()) return emptyList()
        val result = ArrayList<Illust>(count)
        while (result.size < count) {
            val item = queue.poll() ?: break
            if (item.id !in excludedIds) {
                result.add(item)
            }
        }
        return result
    }
}

/**
 * 将提取到的 R-18 作品均匀穿插到基础全年龄作品列表中。
 *
 * 每隔 [step] 个基础作品插入 1 个 R-18 作品。
 * 例如 step = 3：
 * Base[0], Base[1], Base[2], Injected[0], Base[3], Base[4], Base[5], Injected[1]...
 *
 * 保证首屏顶部至少先展示 [step] 个正常推荐作品，且在双列瀑布流中每两行网格（4 张卡片）
 * 稳定对应 3 张正常作品与 1 张 R-18 作品，避免卡片聚集或突兀堆叠。
 */
fun <T> interleaveIllusts(base: List<T>, injected: List<T>, step: Int = RecmdIllustFeedFragment.R18_INTERLEAVE_STEP): List<T> {
    if (injected.isEmpty()) return base
    if (base.isEmpty()) return injected
    if (step <= 0) return injected + base

    val result = ArrayList<T>(base.size + injected.size)
    var injectedIndex = 0
    for (i in base.indices) {
        result.add(base[i])
        if ((i + 1) % step == 0 && injectedIndex < injected.size) {
            result.add(injected[injectedIndex++])
        }
    }
    while (injectedIndex < injected.size) {
        result.add(injected[injectedIndex++])
    }
    return result
}

/**
 * 将 R-18 条目均匀平摊穿插进包含非插画条目（如 [RankPreviewHeaderItem]）的 [FeedItem] 列表中。
 *
 * 保留非插画条目（例如排行榜头部条）在原位，仅在插画卡片（[IllustFeedItem]）序列中
 * 每隔 [step] 个正常作品插入 1 个注入条目。
 */
fun interleaveFeedItems(
    items: List<FeedItem>,
    injected: List<FeedItem>,
    step: Int = RecmdIllustFeedFragment.R18_INTERLEAVE_STEP,
): List<FeedItem> {
    if (injected.isEmpty()) return items
    if (items.isEmpty()) return injected
    if (step <= 0) return injected + items

    val result = ArrayList<FeedItem>(items.size + injected.size)
    var injectedIndex = 0
    var illustCount = 0

    for (item in items) {
        result.add(item)
        if (item is IllustFeedItem) {
            illustCount++
            if (illustCount % step == 0 && injectedIndex < injected.size) {
                result.add(injected[injectedIndex++])
            }
        }
    }
    while (injectedIndex < injected.size) {
        result.add(injected[injectedIndex++])
    }
    return result
}

/** 上下屏前记下的滚动锚点：作品 id + 卡片顶部相对列表 paddingTop 的高度 + 当时的刷新代号。 */
private data class BookmarkScrollAnchor(
    val illustId: Long,
    val offset: Int,
    val generation: Int,
)

/**
 * 维持首页 R-18 推荐缓冲池及其后台注水协程生命周期的 ViewModel。
 * 绑定在 Fragment 的 ViewModelStore 中，在配置变更 / 页面存活期间长期留存。
 */
class RecmdR18ViewModel : ViewModel() {

    val buffer = R18RecommendBuffer()

    private val _loadedGeneration = MutableStateFlow(0)

    /** 每次拉取成功且非空注满缓冲池后自增，Fragment 按 view 生命周期观察它做首屏补偿。 */
    val loadedGeneration: StateFlow<Int> = _loadedGeneration.asStateFlow()

    private var rechargeJob: Job? = null
    private var isFetching: Boolean = false

    /**
     * 在后台协程中为缓冲池异步拉取网页 R-18 推荐作品。与全年龄推荐接口并行出发，两相奔赴。
     *
     * @param apiType "illust" 或 "manga"。
     */
    fun recharge(apiType: String) {
        if (!RecmdIllustFeedFragment.isR18RecommendEligible()) {
            buffer.clear()
            return
        }
        if (isFetching) {
            return
        }
        rechargeJob?.cancel()
        isFetching = true
        rechargeJob = viewModelScope.launch {
            try {
                val r18List = RecmdIllustFeedFragment.fetchWebR18Recommend(apiType)
                if (r18List.isNotEmpty()) {
                    buffer.reset(r18List)
                    Timber.d("RecmdR18ViewModel: recharged %d r18 items into buffer", r18List.size)
                    _loadedGeneration.value += 1
                }
            } finally {
                isFetching = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        rechargeJob?.cancel()
        buffer.clear()
    }
}

