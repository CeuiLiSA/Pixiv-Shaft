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
import androidx.recyclerview.widget.StaggeredGridLayoutManager
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
import ceui.lisa.helper.StaggeredManager
import ceui.lisa.model.ListIllust
import ceui.pixiv.api.model.Illust
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemHorizontalDecoration
import ceui.lisa.view.SpacesItemWithHeadDecoration
import ceui.pixiv.api.Client
import ceui.pixiv.db.discovery.DiscoveryPool
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedLoadPhase
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.pixiv.cachedPixivFeedSource
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustMuteStore
import ceui.pixiv.ui.common.showCardMenu
import ceui.pixiv.ui.common.staggerIllustRenderer
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.utils.setOnClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ceui.pixiv.services.appServices

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
 * - GAP_HANDLING_NONE + SpacesItemWithHeadDecoration（带头瀑布流的间距规则）。
 */
open class RecmdIllustFeedFragment(
    @LayoutRes contentLayoutId: Int = ceui.pixiv.feeds.R.layout.fragment_feed,
) : IllustFeedFragment(contentLayoutId) {

    protected val dataType: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(ARG_DATA_TYPE) ?: TYPE_ILLUST
    }

    override val feedViewModel by feedViewModels {
        // 零捕获约定（见 feedViewModels 文档）：source/mapper 归 VM 长期持有，
        // 只捕获局部值、映射走伴生函数，不把 Fragment 实例钉进 VM
        val dataType = dataType
        val apiType = if (dataType == TYPE_MANGA) "manga" else "illust"
        // 应用级对象，捕获它不会把 Fragment 钉进 VM
        val pool = requireContext().appServices().discoveryPool
        // 本地优先（哔哩哔哩 / 新闻首页语义）：给稳定 slot 即开磁盘缓存，冷启秒显上次首屏
        // 再拉最新覆盖。slot 已由框架自动拼账号命名空间，切号不串味。
        cachedPixivFeedSource(
            slot = "recmd-$apiType",
            initialFetch = { Client.appApi.getRecommendedWorksWithRanking(apiType) },
            // 「启动时自动刷新首页推荐」(issue #955)：关掉后冷启命中快照就停在快照上，
            // 由用户下拉刷新才换一批——推荐流每次冷启整代替换，会让上次没翻完的作品直接消失。
            // 只作用于首页那份实例：推荐漫画是从别处点进去的独立页面，不属于「启动」语义。
            // lambda 每次刷新现读设置（不用重建数据源），效果本就只在下次冷启看得到。
            refreshAfterCacheHit = {
                dataType != TYPE_ILLUST || Shaft.sSettings.isAutoRefreshHomeFeed
            },
        ) { resp, phase ->
            mapRecmdPage(pool, resp.illusts, resp.ranking_illusts, phase, dataType)
        }
    }

    /** 收藏时顺带拉相关作品插入列表（本页专属设置）。 */
    override val showRelatedOnStar: Boolean
        get() = Shaft.sSettings.isShowRelatedWhenStar

    /** 排行榜预览头携带的 bean 也要合池 + 灌关注状态（对齐 legacy onFirstLoaded）。 */
    override fun poolableBeansOf(item: FeedItem): List<Illust> {
        return if (item is RankPreviewHeaderItem) item.rankBeans else super.poolableBeansOf(item)
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
            }
        }
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(relatedReceiver)
        super.onDestroyView()
    }

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        // GAP_HANDLING_NONE 对齐 legacy：带整行 header 的瀑布流开 gap 策略会在回滚时重排跳动
        return StaggeredManager(Shaft.sSettings.lineCount, RecyclerView.VERTICAL).apply {
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        }
    }

    override fun onListReady(listView: RecyclerView) {
        listView.addItemDecoration(SpacesItemWithHeadDecoration(DensityUtil.dp2px(8.0f)))
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

        /** dataType 是路由字面量（RankActivity 按 "插画"/"漫画" 分支），不是展示文案，别本地化。 */
        const val TYPE_ILLUST = "插画"
        const val TYPE_MANGA = "漫画"

        @JvmStatic
        fun newInstance(dataType: String): RecmdIllustFeedFragment {
            return RecmdIllustFeedFragment().apply {
                arguments = Bundle().apply { putString(ARG_DATA_TYPE, dataType) }
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
            // 缓存恢复不喂（旧数据画像无意义、且违反重放安全）。
            if (phase.isFreshFetch) {
                pool.collect(
                    illusts,
                    if (phase.isFirstPage) "recmd:$dataType" else "recmd_next:$dataType",
                )
            }
            val listItems = illusts.mapNotNull { IllustFeedItem.of(it) }
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
