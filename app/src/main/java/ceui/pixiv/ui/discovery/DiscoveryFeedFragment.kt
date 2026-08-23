package ceui.pixiv.ui.discovery

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.loxia.Illust
import ceui.pixiv.db.discovery.DiscoveryPool
import ceui.pixiv.db.discovery.ProfileManager
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 「发现」（算法流）页，feeds 框架版，替代 legacy FragmentDiscovery。
 *
 * 与其它 feeds 页不同：数据源是**本地** [DiscoveryPool]（Room 候选池），不是 pixiv 的 nextUrl
 * 接口，所以不走 PixivFeedSource，直接实现 [FeedSource]——游标就是页序号。
 *
 * 两处与 legacy 的行为差异，都是刻意的：
 * - **到底判定**：legacy 的 `hasNext()` 恒为 true，池子掏空后仍会无限尝试翻页；这里池子返回空
 *   即 `nextCursor = null`，列表正常收尾到「没有更多」。（候选池展示后不回收、翻几页就耗尽是
 *   issue #937，本次不动。）
 * - **内容过滤**：legacy 直接把池子里的东西塞进列表，不过屏蔽/R18/AI 过滤链；这里走
 *   [IllustFeedItem.of]，与全仓其它列表同口径。整页被滤空由 FeedViewModel 空页追载兜住。
 *
 * [markShown] 的标记口径与 legacy 保持一致（解析成功且 id>0 就标记，不管之后是否被过滤掉）——
 * 被过滤的条目若不标记，下次仍会被池子选中，白白触发空页追载。
 */
class DiscoveryFeedFragment : IllustFeedFragment(R.layout.fragment_toolbar_feed) {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    override val feedViewModel by feedViewModels {
        // 游标类型被 IllustFeedFragment 钉成 String（pixiv 列表的游标就是 nextUrl）；本地池子
        // 没有 URL，这里只拿它当页号用，并覆写 detailContinuationCursor 不让它漏进详情页。
        FeedSource<String> { cursor ->
            val page = cursor?.toIntOrNull() ?: 0
            // 池子读写都是同步 Room 调用，FeedSource.load 的 main-safe 契约要求自己切 IO。
            val (poolSize, items) = withContext(Dispatchers.IO) {
                val entities = DiscoveryPool.getDiscoveryFeedDiversified(PAGE_SIZE)
                entities.size to convertAndMark(entities)
            }
            Timber.d("%s page=%d: %d entities -> %d illusts", TAG, page, poolSize, items.size)
            FeedPage(
                items = items,
                // 池子掏空 = 到底；否则继续翻（legacy 靠 markShown + recentlyReturnedIds 防重复，
                // 不需要 offset，游标只用来记页号）。
                nextCursor = if (poolSize == 0) null else (page + 1).toString(),
            )
        }
    }

    /** 本地池子没有 nextUrl，详情页 pager 不能续读——喂页号进去只会当 URL 请求失败。 */
    override val detailContinuationCursor: String? get() = null

    // 候选池的 illustJson 是**采集那一刻**冻结的快照，可能已存了几天。喂池会拿旧的
    // is_bookmarked=false / user.is_followed 盖掉当前会话里更新的收藏/关注态
    // （mergeKeepingExisting 不把 false 当空值）。同 WatchLaterFeedFragment 先例。
    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    /** 候选池在库总数归 VM（数据不塞 Fragment），旋转 / 视图重建后不重查。 */
    private val countViewModel: DiscoveryPoolCountViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.setText(R.string.string_discovery)
        bindPoolCount()
        logDiagnostics()
    }

    /**
     * toolbar 副标题「已展示 1–N，共 M」：N 跟着列表增长（翻页即时变），M 是候选池在库总数。
     * 池子总数未知（首次查询未回来 / 查询失败）或列表还空着时不显示，不占一行空文字。
     */
    private fun bindPoolCount() {
        val loadedCount = feedViewModel.uiState
            .map { state -> state.items.count { it is IllustFeedItem } }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 每次页面可见重查一次：详情页翻着翻着采集器可能又往池子里灌了新候选。
                countViewModel.reload()
                combine(loadedCount, countViewModel.total) { loaded, total -> loaded to total }
                    .distinctUntilChanged()
                    .collect { (loaded, total) ->
                        val subtitle = binding.toolbarSubtitle
                        if (loaded == 0 || total == null || total <= 0) {
                            subtitle.isVisible = false
                            return@collect
                        }
                        subtitle.isVisible = true
                        subtitle.text = getString(R.string.feed_count_range, 1, loaded, total)
                    }
            }
        }
    }

    /**
     * 保留 legacy 的画像/池子诊断日志：#937 之类的候选池问题全靠这几行定位。
     * [DiscoveryPool.getStats] 是同步 Room 查询（legacy 在 initView 里直接跑在主线程），
     * 挪到 IO 上打。
     */
    private fun logDiagnostics() {
        viewLifecycleOwner.lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                runCatching { DiscoveryPool.getStats() }.getOrElse { "unavailable" }
            }
            Timber.d("%s pool: %s", TAG, stats)
        }
        val profile = ProfileManager.cached()
        if (profile == null) {
            Timber.d("%s profile NOT READY", TAG)
            return
        }
        Timber.d(
            "%s profile: %d tags, %d authors, %d seeds, avgRate=%.4f",
            TAG, profile.tagScores.size, profile.authorScores.size,
            profile.seedIllusts.size, profile.avgBookmarkRate,
        )
        profile.topTags(5).forEach { (tag, lift) ->
            Timber.d("%s   top tag: '%s' lift=%.2f", TAG, tag, lift)
        }
    }

    /**
     * toolbar 计数用的候选池在库总数。单独一个小 VM 而不是塞进 FeedViewModel：
     * 它跟列表翻页状态没关系（数据归 ViewModel + 按需建小 VM，别拉大杂烩）。
     */
    class DiscoveryPoolCountViewModel : ViewModel() {

        private val _total = MutableStateFlow<Int?>(null)

        /** null = 还没查出来 / 查询失败——UI 据此不显示计数，而不是显示一个假的 0。 */
        val total: StateFlow<Int?> = _total.asStateFlow()

        init {
            reload()
        }

        fun reload() {
            viewModelScope.launch {
                val count = withContext(Dispatchers.IO) {
                    runCatching { DiscoveryPool.totalCount() }
                        .onFailure { Timber.w(it, "%s totalCount failed", TAG) }
                        .getOrNull()
                }
                if (count != null) _total.value = count
            }
        }
    }

    companion object {
        private const val TAG = "Discovery/Feed"
        private const val PAGE_SIZE = 30

        /**
         * 池子实体 → feed 条目。零捕获（只碰 object / 全局 gson），可以安全地活到 VM 生命周期。
         *
         * 逐条日志沿用 legacy 的字段（id/score/source/标题/作者）：调分数权重、查 #937 这类
         * 候选池问题就靠它。多打一个 filtered 标记——legacy 不过滤，没有这个状态。
         */
        private fun convertAndMark(entities: List<ceui.pixiv.db.DiscoveryEntity>): List<IllustFeedItem> {
            val result = mutableListOf<IllustFeedItem>()
            entities.forEach { entity ->
                val bean = runCatching {
                    Shaft.sGson.fromJson(entity.illustJson, Illust::class.java)
                }.onFailure {
                    Timber.w(it, "%s   parse FAILED id=%d", TAG, entity.illustId)
                }.getOrNull()
                if (bean == null || bean.id <= 0) return@forEach
                DiscoveryPool.markShown(entity.illustId)
                val item = IllustFeedItem.of(bean)
                item?.let(result::add)
                Timber.d(
                    "%s   %s id=%d score=%.2f source='%s' '%s' by '%s'",
                    TAG, if (item == null) "filtered" else "display",
                    entity.illustId, entity.score, entity.source, bean.title,
                    bean.user?.name ?: "?",
                )
            }
            return result
        }
    }
}
