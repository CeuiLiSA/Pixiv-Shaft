package ceui.pixiv.ui.library

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentBookmarkLibraryBinding
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.Params
import ceui.loxia.appServices
import ceui.pixiv.db.mirror.AgeFilter
import ceui.pixiv.db.mirror.AiFilter
import ceui.pixiv.db.mirror.BookmarkFilter
import ceui.pixiv.db.mirror.BookmarkMirrorStateEntity
import ceui.pixiv.db.mirror.BookmarkShelf
import ceui.pixiv.db.mirror.BookmarkSort
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.db.mirror.MirrorPhase
import ceui.pixiv.db.mirror.MirrorRestrict
import ceui.pixiv.db.mirror.PageFilter
import ceui.pixiv.db.mirror.ValidityFilter
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.ui.navigation.TemplateRoute
import com.blankj.utilcode.util.BarUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/**
 * 收藏库页面的全部接线，插画版与小说版**共用同一份**。
 *
 * 为什么单独成一个类而不是放进某个基类：插画列表继承 [ceui.pixiv.ui.common.IllustFeedFragment]、
 * 小说列表继承 [ceui.pixiv.ui.common.NovelFeedFragment]（两套完全不同的卡片、点击语义、
 * 骨架图和 LayoutManager），Kotlin 又没有多继承。要么把这三百多行复制两份、从此各自漂移，
 * 要么抽成一个只依赖「binding + 列表 VM + feed VM」的普通类 —— 后者显然更划算：
 * 两个页面的差异其实只有「一行 payload 解析成插画卡还是小说卡」这一处。
 *
 * 生命周期跟着 **view** 走：宿主在 onViewCreated 建、onDestroyView 调 [destroy]。
 * 所有可变的视图态（chip 引用、状态快照、待重置代号）都收在这里，宿主自己不留。
 */
internal class BookmarkLibraryUi(
    private val fragment: Fragment,
    private val binding: FragmentBookmarkLibraryBinding,
    private val listView: RecyclerView,
    private val viewModel: BookmarkLibraryViewModel,
    private val feedViewModel: FeedViewModel<String>,
    private val contentType: MirrorContentType,
    /** 当前列表上有多少条目。空列表时的自动重查要用（见 [refreshIfStillEmpty]）。 */
    private val itemCount: () -> Int,
) {

    private var sortChip: TextView? = null
    private var reverseChip: TextView? = null
    private var randomChip: TextView? = null
    private var filterChip: TextView? = null
    private var clearChip: TextView? = null

    private var pendingSearch: Runnable? = null
    private var destroyed = false

    /**
     * 触发换条件时列表所处的「代号」。等到提交上来的代号**变了**（= 新一代真的落地了）
     * 才把列表拨回顶部，见 [applyFilterChange] / [onListCommitted]。
     */
    private var resetAfterGeneration: Int? = null

    /** 最近一次拿到的全部书架状态；切书架时按当前 shelfKey 重新挑一条出来。 */
    private var latestStates: List<BookmarkMirrorStateEntity> = emptyList()

    private val context get() = fragment.requireContext()

    private val isIllust get() = contentType == MirrorContentType.ILLUST

    // ─────────────────────────── 装配 ───────────────────────────

    fun install() {
        setUpToolbar()
        setUpMenu()
        setUpShelfSwitch()
        setUpSearch()
        setUpChips()
        observeState()

        // 打开收藏库本身就是一次「用户在看这个书架」的信号：让引擎马上补一次增量，
        // 而不是等下一个例行窗口。补的过程静默，页面照常用本地数据。
        context.appServices().bookmarkMirror.ensureShelf(viewModel.shelf, reason = "打开收藏库")
    }

    fun destroy() {
        destroyed = true
        pendingSearch?.let { binding.searchInput.removeCallbacks(it) }
        pendingSearch = null
        sortChip = null
        reverseChip = null
        randomChip = null
        filterChip = null
        clearChip = null
    }

    // ─────────────────────────── 对外 ───────────────────────────

    /**
     * 换筛选 / 排序 / 书架之后重新出列表。
     *
     * **不能用 `FeedFragment.forceRefresh()`**：它先 `smoothScrollToPosition(0)` 再刷新，
     * 而平滑滚动带动画、结束时机跟新一代数据落地是两回事；更要紧的是框架只在「新旧两代
     * 真的会撕」时才顺带清 LayoutManager 的跨代残留，走 DiffUtil 那条分支就不清。而本页
     * 每次换条件都是整代替换，上一代留在 SGLM 里的 span 偏移会让新一代首屏**左列顶部空出
     * 一大块**（真机截图复现过）。所以这里记下当前代号，等新一代真正提交完再确定性地复位。
     */
    fun applyFilterChange() {
        resetAfterGeneration = feedViewModel.uiState.value.refreshGeneration
        feedViewModel.refresh()
    }

    fun onListCommitted(state: FeedUiState) {
        // 认代号而不是认一个布尔标记：onListCommitted 每次提交都会来，**包括往下滑追加的页**。
        // 用布尔的话，只要有一次「置了标记但那一代刷新没提交上来」（连续快切时前一次 refresh
        // 会被后一次 cancel），残留的 true 就会被下一次追加页消费掉 —— 用户正滑到一半，
        // 列表突然被拨回顶部。代号变了才动手，追加页的代号不变，绝不会误伤。
        val target = resetAfterGeneration ?: return
        if (state.refreshGeneration == target) return
        resetAfterGeneration = null
        when (val manager = listView.layoutManager) {
            is StaggeredGridLayoutManager -> {
                // invalidateSpanAssignments 是唯一能清掉上一代 span 偏移的公开 API；
                // scrollToPositionWithOffset(0,0) 把偏移也定死，否则会拿旧 anchor 凑一个
                // 偏移出来，刷完停在「顶部偏下」。
                manager.invalidateSpanAssignments()
                manager.scrollToPositionWithOffset(0, 0)
            }
            is LinearLayoutManager -> manager.scrollToPositionWithOffset(0, 0)
            else -> manager?.scrollToPosition(0)
        }
    }

    /**
     * 空态要分清三种「空」，不然用户没法知道该等还是该改条件：
     * 条件筛没了 / 镜像还没补到这个书架 / 是真的一件都没收藏。
     */
    fun emptyStateText(): CharSequence = when {
        viewModel.filter.value.hasAnyCondition ->
            context.getString(R.string.bookmark_library_empty_filtered)
        viewModel.mirrorState.value?.isFirstSyncDone == false ->
            context.getString(R.string.bookmark_library_empty_syncing)
        else -> context.getString(R.string.bookmark_library_empty)
    }

    // ─────────────────────────── 接线 ───────────────────────────

    /**
     * fragment_toolbar_feed 那套 AppCompat toolbar 的自装版（`setUpToolbar` 只吃
     * FragmentToolbarFeedBinding，本页有自己的骨架）。
     * BaseActivity 开了 EdgeToEdge：状态栏 inset 走 BarUtils 手动 padding，不用
     * fitsSystemWindows（会把 status + nav 两个 inset 都当 padding 套上）；
     * 底部导航栏的高度让给列表，不然最后一排卡片压在手势条底下。
     */
    private fun setUpToolbar() {
        binding.toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        binding.toolbar.setNavigationOnClickListener { fragment.requireActivity().finish() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            listView.updatePadding(0, 0, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setUpMenu() {
        // 「原始收藏列表」是留给「我要看服务端原本的样子」的退路：本页展示的是本地镜像
        //（顺序是本地重排的，内容取的是镜像时冻结的快照）。公开/私人本页已经能直接切，
        // 所以这条退路只为「原序 + 最新」这两点存在。
        binding.toolbar.menu.add(0, MENU_CLASSIC, 0, R.string.bookmark_library_open_classic)
        binding.toolbar.menu.add(0, MENU_REBUILD, 1, R.string.bookmark_library_rebuild)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_CLASSIC -> {
                    openClassicCollection()
                    true
                }
                MENU_REBUILD -> {
                    rebuildMirror()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 打开原始的双 tab 收藏页。带 [Params.FLAG] 标记，那边据此**不再**把入口重定向回本页，
     * 否则用户从本页点进去会被立刻弹回来，两个页面互相踢皮球。
     */
    private fun openClassicCollection() {
        val route = if (isIllust) TemplateRoute.MY_ILLUST_COLLECTION else TemplateRoute.MY_NOVEL_COLLECTION
        fragment.startActivity(
            Intent(context, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, route.key)
                putExtra(Params.FLAG, true)
            }
        )
    }

    private fun rebuildMirror() {
        val shelf = viewModel.shelf
        Timber.tag(TAG).i("用户手动重建镜像 %s", shelf.label)
        context.appServices().bookmarkMirror.rebuildShelf(shelf)
        applyFilterChange()
    }

    /**
     * 公开 / 悄悄收藏切换。点「悄悄收藏」本身就是一次明确的用户意图，所以顺带把那个书架
     * 注册进镜像（`trackBookmarkShelfVisit` 同款的隐私边界：没主动看过就不会去拉）。
     */
    private fun setUpShelfSwitch() {
        binding.shelfPublic.setText(if (isIllust) R.string.public_like_illust else R.string.public_like_novel)
        binding.shelfPrivate.setText(if (isIllust) R.string.private_like_illust else R.string.private_like_novel)
        binding.shelfPublic.setOnClickListener { switchShelf(MirrorRestrict.PUBLIC) }
        binding.shelfPrivate.setOnClickListener { switchShelf(MirrorRestrict.PRIVATE) }
        renderShelfSwitch()
    }

    private fun switchShelf(restrict: MirrorRestrict) {
        val current = viewModel.shelf
        if (current.restrict == restrict) return
        val next = current.copy(restrict = restrict)
        if (!viewModel.switchShelf(next)) return
        binding.searchInput.setText("")
        context.appServices().bookmarkMirror
            .ensureShelf(next, reason = "收藏库切换到${next.restrict.apiValue}")
        renderShelfSwitch()
        renderChips()
        applyMirrorState()
        applyFilterChange()
    }

    private fun renderShelfSwitch() {
        val isPublic = viewModel.shelf.restrict == MirrorRestrict.PUBLIC
        // 分段按钮的 drawable / 字色选择器认的是 state_selected，不是 activated
        binding.shelfPublic.isSelected = isPublic
        binding.shelfPrivate.isSelected = !isPublic
    }

    private fun setUpSearch() {
        val input = binding.searchInput
        input.setText(viewModel.filter.value.keyword)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString().orEmpty()
                pendingSearch?.let { input.removeCallbacks(it) }
                val task = Runnable {
                    if (destroyed) return@Runnable
                    if (viewModel.updateFilter { it.copy(keyword = keyword) }) applyFilterChange()
                }
                pendingSearch = task
                input.postDelayed(task, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    private fun setUpChips() {
        val row = binding.chipRow
        row.removeAllViews()

        sortChip = addChip(row, "") { BookmarkFilterSheet.show(fragment) }
        // 倒序是本页存在的直接理由（#1323），必须是一键，不能埋进面板
        reverseChip = addChip(row, context.getString(R.string.bookmark_chip_oldest_first)) {
            val next = if (viewModel.filter.value.sort == BookmarkSort.BOOKMARK_OLDEST) {
                BookmarkSort.BOOKMARK_NEWEST
            } else {
                BookmarkSort.BOOKMARK_OLDEST
            }
            if (viewModel.updateFilter { it.copy(sort = next) }) applyFilterChange()
        }
        randomChip = addChip(row, context.getString(R.string.bookmark_chip_random)) {
            // 已经在随机态时再点 = 重新洗牌，所以种子每次都换
            val alreadyRandom = viewModel.filter.value.sort.isRandom
            val changed = viewModel.updateFilter {
                if (alreadyRandom) {
                    it.copy(randomSeed = System.currentTimeMillis())
                } else {
                    it.copy(sort = BookmarkSort.RANDOM, randomSeed = System.currentTimeMillis())
                }
            }
            if (changed) applyFilterChange()
        }
        filterChip = addChip(row, context.getString(R.string.bookmark_chip_filter)) {
            BookmarkFilterSheet.show(fragment)
        }
        clearChip = addChip(row, context.getString(R.string.bookmark_chip_clear)) {
            binding.searchInput.setText("")
            if (viewModel.clearConditions()) applyFilterChange()
        }
        renderChips()
    }

    private fun addChip(row: ViewGroup, text: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(context.resources.getColorStateList(R.color.bookmark_chip_text, null))
            setBackgroundResource(R.drawable.bg_bookmark_chip)
            updatePadding(
                left = DensityUtil.dp2px(14f), right = DensityUtil.dp2px(14f),
                top = DensityUtil.dp2px(7f), bottom = DensityUtil.dp2px(7f),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.rightMargin = DensityUtil.dp2px(8f) }
            row.addView(this)
        }

    private fun renderChips() {
        val filter = viewModel.filter.value
        // 条件被别处清空了（筛选面板里的「清空」），搜索框要跟着空掉。
        // 只做「清空」这一个方向、且只在框里确实还有字时动手：绝不拿 filter 去覆盖用户
        // 正在敲的内容 —— 输入防抖期间 filter 根本不会发射，所以这里也不会和打字打架。
        val input = binding.searchInput
        if (filter.keyword.isEmpty() && input.text.isNotEmpty()) input.setText("")

        sortChip?.text = context.getString(sortLabelRes(filter.sort))
        // 排序 chip 只在「不是默认排序」时点亮：默认态点亮一片，选中态就不再是信息了
        sortChip?.isActivated = filter.sort != BookmarkSort.BOOKMARK_NEWEST
        reverseChip?.isActivated = filter.sort == BookmarkSort.BOOKMARK_OLDEST
        randomChip?.isActivated = filter.sort.isRandom

        val conditions = countConditions(filter)
        filterChip?.text = if (conditions > 0) {
            context.getString(R.string.bookmark_chip_filter_count, conditions)
        } else {
            context.getString(R.string.bookmark_chip_filter)
        }
        filterChip?.isActivated = conditions > 0
        // 「清空」只在真有东西可清时出现：常驻一个永远灰着的按钮只是噪音
        clearChip?.visibility = if (filter.hasAnyCondition) View.VISIBLE else View.GONE
    }

    /** chip 上那个数字：用户开了几个筛选维度。排序不算——它不减少结果。 */
    private fun countConditions(filter: BookmarkFilter): Int {
        var count = 0
        if (filter.keyword.isNotBlank()) count++
        if (filter.tagNames.isNotEmpty()) count++
        if (filter.excludedTagNames.isNotEmpty()) count++
        if (filter.authorIds.isNotEmpty()) count++
        if (filter.workTypes.isNotEmpty()) count++
        if (filter.orientations.isNotEmpty()) count++
        if (filter.ai != AiFilter.ANY) count++
        if (filter.age != AgeFilter.ANY) count++
        if (filter.pages != PageFilter.ANY) count++
        if (filter.validity != ValidityFilter.ANY) count++
        if (filter.minBookmarks != null || filter.maxBookmarks != null) count++
        if (filter.minTextLength != null || filter.maxTextLength != null) count++
        if (filter.createdFromMs != null || filter.createdToMs != null) count++
        if (filter.seriesOnly) count++
        return count
    }

    private fun observeState() {
        val uid = viewModel.shelf.ownerUid
        val mirror = context.appServices().bookmarkMirror
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.filter.collectLatest { renderChips() } }
                launch {
                    // 进度条上的「已 N 件」跟着镜像行数走
                    viewModel.totalCount.collectLatest { renderSyncBanner() }
                }
                launch {
                    mirror.observeState(uid).collectLatest { states ->
                        latestStates = states
                        applyMirrorState()
                    }
                }
                launch {
                    // 按 owner 订阅：页面可以就地切书架，按 shelfKey 订的话每切一次都要重订
                    mirror.observeOwnerCount(uid).collectLatest {
                        viewModel.onMirrorChanged()
                        renderSyncBanner()
                        refreshIfStillEmpty()
                    }
                }
            }
        }
    }

    /**
     * 后台刚往镜像里写进一批，而屏幕上还是空的 → 自动重查一次。
     *
     * **这条是闭环的关键**：本地源查到 0 行时返回的 `nextCursor` 是 null，feeds 框架
     * 据此判定「到底了」，从此不会再问数据源要任何东西。于是「切到一个从没镜像过的书架」
     * 或「刚点了重建镜像」时，页面会永久停在空态 —— 而它头顶那条进度条还在一秒一秒地
     * 数「正在补齐 · 已 300 件」。用户看到的是两句互相打脸的话，唯一出路是自己想到下拉刷新。
     *
     * 三道门缺一不可：
     * - 只在**镜像还没补完**时管。少了它每次进页面都会白查一遍：Room 的 Flow 一订阅就先发
     *   一次当前值，而那一刻首屏查询还没提交进 uiState，列表当然是空的。
     * - 只在**列表确实为空**时管。有内容时后台每 5 秒写一页，跟着刷就等于每 5 秒把用户的
     *   滚动位置拽回顶部一次；而且本来也不需要 —— 本地分页每页都是新查询，往下滑自然读得到。
     * - **有筛选条件时不管**。那种「空」是条件没筛到，不是数据没到，重查一百次还是空。
     */
    private fun refreshIfStillEmpty() {
        if (destroyed) return
        if (viewModel.mirrorState.value?.isFirstSyncDone != false) return
        if (viewModel.filter.value.hasAnyCondition) return
        if (itemCount() > 0) return
        Timber.tag(TAG).d("镜像仍在补齐而列表为空，自动重查")
        applyFilterChange()
    }

    /** 从最近一份状态列表里挑出**当前**书架那条，喂给 VM 并重画进度条。 */
    private fun applyMirrorState() {
        val key = viewModel.shelf.key
        viewModel.setMirrorState(latestStates.firstOrNull { it.shelfKey == key })
        renderSyncBanner()
    }

    private fun renderSyncBanner() {
        val state = viewModel.mirrorState.value
        val total = viewModel.totalCount.value ?: 0
        // 补齐过一次之后这条就永远不再出现 —— 「同步完成过一次，以后只维护」的界面表达。
        val syncing = state != null && !state.isFirstSyncDone
        binding.syncBanner.visibility = if (syncing) View.VISIBLE else View.GONE
        if (!syncing || state == null) return
        binding.syncText.text = when {
            state.cooldownUntil > System.currentTimeMillis() ->
                context.getString(R.string.bookmark_library_sync_cooldown)
            state.phase == MirrorPhase.BACKFILLING ->
                context.getString(R.string.bookmark_library_syncing, formatCount(total))
            else -> context.getString(R.string.bookmark_library_sync_queued)
        }
    }

    private fun sortLabelRes(sort: BookmarkSort): Int = when (sort) {
        BookmarkSort.BOOKMARK_NEWEST -> R.string.bookmark_sort_bookmark_newest
        BookmarkSort.BOOKMARK_OLDEST -> R.string.bookmark_sort_bookmark_oldest
        BookmarkSort.CREATED_NEWEST -> R.string.bookmark_sort_created_newest
        BookmarkSort.CREATED_OLDEST -> R.string.bookmark_sort_created_oldest
        BookmarkSort.POPULAR_DESC -> R.string.bookmark_sort_popular_desc
        BookmarkSort.POPULAR_ASC -> R.string.bookmark_sort_popular_asc
        BookmarkSort.VIEWS_DESC -> R.string.bookmark_sort_views_desc
        BookmarkSort.PAGES_DESC -> R.string.bookmark_sort_pages_desc
        BookmarkSort.LENGTH_DESC -> R.string.bookmark_sort_length_desc
        BookmarkSort.LENGTH_ASC -> R.string.bookmark_sort_length_asc
        BookmarkSort.TITLE_ASC -> R.string.bookmark_sort_title_asc
        BookmarkSort.RANDOM -> R.string.bookmark_sort_random
    }

    private fun formatCount(value: Int): String = String.format(Locale.getDefault(), "%,d", value)

    companion object {
        private const val TAG = "BookmarkLibrary"
        private const val SEARCH_DEBOUNCE_MS = 280L
        private const val MENU_REBUILD = 1
        private const val MENU_CLASSIC = 2

        /** 内容类型入参。**必须由路由方引用这个常量**，别在别处再抄一遍字面量。 */
        const val ARG_CONTENT_TYPE = "bookmark_library_content_type"

        /** 从入参解出本页要看的书架。 */
        fun shelfFromArguments(args: android.os.Bundle, ownerUid: Long): BookmarkShelf = BookmarkShelf(
            ownerUid = Params.getUserId(args).takeIf { it > 0L } ?: ownerUid,
            contentType = MirrorContentType.of(args.getInt(ARG_CONTENT_TYPE, 0))
                ?: MirrorContentType.ILLUST,
            restrict = MirrorRestrict.ofApiValue(args.getString(Params.STAR_TYPE)),
        )
    }
}
