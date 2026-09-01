package ceui.pixiv.ui.library

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.content.Intent
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentBookmarkLibraryBinding
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.Params
import com.blankj.utilcode.util.BarUtils
import ceui.loxia.Illust
import ceui.loxia.appServices
import ceui.pixiv.db.mirror.BookmarkShelf
import ceui.pixiv.db.mirror.BookmarkSort
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.db.mirror.MirrorPhase
import ceui.pixiv.db.mirror.MirrorRestrict
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.common.IllustFeedFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/**
 * 收藏库 —— 直接看本地镜像表（[ceui.pixiv.db.mirror.BookmarkMirrorEntity]）的收藏列表页。
 *
 * ## 它和「我的插画收藏」是什么关系
 *
 * 「我的插画收藏」是**服务端顺序**的原样列表：只能从新到旧，翻到哪算哪。本页是同一批
 * 收藏的**本地副本**，所以能做服务端做不到的一切：倒序（友商 pixez #1323 的诉求）、
 * 按标签/作者/年份/画幅/人气筛、全文搜、随机漫游 —— 而且全在 SQLite 里，一次网络请求都不发。
 *
 * 两者不是替代关系：镜像还没补齐的时候，原列表永远是最新最全的那份。所以本页在补齐之前
 * 会挂一条进度条老实说「还在补」，补齐之后那条就永远消失。
 *
 * ## 交互设计（V3 / MD3-E）
 *
 * - **一屏之内把最常用的三件事做完**：搜索框、`倒序` 一键切换、`随机` 一键漫游。
 *   倒序是本页存在的直接理由，绝不能藏进二级面板里；
 * - 其余十几个维度收进 [BookmarkFilterSheet]，chip 上带条件数，用户永远知道自己开了几个筛选；
 * - 选中态用主题色实底胶囊表达（MD3-E 的形状语汇），不是加一圈描边。
 *
 * ## 数据取舍
 *
 * 卡片 bean 来自镜像行里冻结的 JSON，所以：
 * - 不喂 ObjectPool（[poolableBeansOf] 返回空）—— 旧快照会盖掉用户这次会话里更新的收藏/关注态；
 * - 不给详情页续拉游标（[detailContinuationCursor] 恒 null）—— 本地 offset 流到那条路上会被当 URL 请求。
 * 两条的完整论证同「稍后再看」页（`WatchLaterFeedFragment`）。
 */
class BookmarkLibraryFragment :
    IllustFeedFragment(R.layout.fragment_bookmark_library),
    BookmarkFilterSheet.Host {

    /**
     * 筛选面板改完条件回调。**必须有**：面板是即时生效语义（每点一下命中数就变），
     * 但命中数只是 VM 状态，列表本体要靠这一下 [applyFilterChange] 才会用新条件重查。
     * 漏了它的表现极隐蔽——面板里数字一路在变、关掉却还是原来那批图。
     */
    override fun onBookmarkFilterChanged() {
        applyFilterChange()
    }

    /**
     * 本页看的是哪个书架。
     *
     * ⚠️ 目前只支持插画书架：本页继承 [IllustFeedFragment]，卡片渲染吃的是 [Illust]，
     * 把小说行的 JSON 塞进去会解析成一张字段全空的坏插画卡。数据层（表、引擎、查询）
     * 对小说是完全就绪的 —— 小说版只差一个继承 NovelFeedFragment 的兄弟页面，
     * 那时把这里的强制改成按 contentType 分流即可。在此之前**宁可强制回插画**，
     * 也不让一个错误的入参渲染出一屏坏卡。
     */
    private val initialShelf: BookmarkShelf by lazy(LazyThreadSafetyMode.NONE) {
        val args = requireArguments()
        val requested = MirrorContentType.of(args.getInt(ARG_CONTENT_TYPE, 0)) ?: MirrorContentType.ILLUST
        if (requested != MirrorContentType.ILLUST) {
            Timber.tag(TAG).w("收藏库暂不支持 %s 书架，回落到插画", requested.tag)
        }
        BookmarkShelf(
            ownerUid = Params.getUserId(args).takeIf { it > 0L } ?: SessionManager.loggedInUid,
            contentType = MirrorContentType.ILLUST,
            restrict = MirrorRestrict.ofApiValue(args.getString(Params.STAR_TYPE)),
        )
    }

    private val libraryViewModel: BookmarkLibraryViewModel by viewModels()

    override val feedViewModel by feedViewModels {
        // 零捕获：只捕获兄弟 VM（同一 ViewModelStore、同生命周期），不碰 Fragment。
        // bind 是幂等的，谁先初始化都行。
        val vm = libraryViewModel.also { it.bind(initialShelf) }
        BookmarkLibraryFeedSource(vm)
    }

    private var _libraryBinding: FragmentBookmarkLibraryBinding? = null
    private val libraryBinding get() = _libraryBinding!!

    private var sortChip: TextView? = null
    private var reverseChip: TextView? = null
    private var randomChip: TextView? = null
    private var filterChip: TextView? = null
    private var clearChip: TextView? = null

    /** 搜索防抖：每敲一个字都重查一次库是白花力气，用户还没打完。 */
    private var pendingSearch: Runnable? = null

    /**
     * 触发换条件时列表所处的「代号」。等到提交上来的代号**变了**（= 新一代真的落地了）
     * 才把瀑布流拨回顶部，见 [applyFilterChange] / [onListCommitted]。
     */
    private var resetAfterGeneration: Int? = null

    override val detailContinuationCursor: String? get() = null

    /**
     * 换筛选 / 排序 / 书架之后重新出列表。
     *
     * **不能用 `forceRefresh()`**：它先 `smoothScrollToPosition(0)` 再刷新，而平滑滚动
     * 是带动画的、结束时机跟新一代数据落地是两回事；更要紧的是框架只在「新旧两代真的会撕」
     * 时才顺带清 SGLM 的跨代残留（[FeedFragment] 的 resetToTop），走 DiffUtil 那条分支就不清。
     * 而本页每次换条件都是整代替换，上一代留在 LayoutManager 里的 span 偏移会让新一代首屏
     * **左列顶部空出一大块**（真机截图复现过）。
     * 所以这里自己置一个标记，等新列表真正提交完（[onListCommitted]）再确定性地清 span、
     * 把偏移一起钉到 (0, 0)。
     */
    private fun applyFilterChange() {
        resetAfterGeneration = feedViewModel.uiState.value.refreshGeneration
        feedViewModel.refresh()
    }

    override fun onListCommitted(state: ceui.pixiv.feeds.FeedUiState) {
        super.onListCommitted(state)
        // 认代号而不是认一个布尔标记：[onListCommitted] 每次提交都会来，**包括往下滑追加的页**。
        // 用布尔的话，只要有一次「置了标记但那一代刷新没提交上来」（连续快切时前一次 refresh
        // 会被后一次 cancel），残留的 true 就会被下一次追加页消费掉 —— 用户正滑到一半，
        // 列表突然被拨回顶部。代号变了才动手，追加页的代号不变，绝不会误伤。
        val target = resetAfterGeneration ?: return
        if (state.refreshGeneration == target) return
        resetAfterGeneration = null
        val manager = _libraryBinding?.let { feedBinding.feedListView.layoutManager }
        (manager as? StaggeredGridLayoutManager)?.apply {
            // invalidateSpanAssignments 是唯一能清掉上一代 span 偏移的公开 API；
            // scrollToPositionWithOffset(0,0) 把偏移也定死，否则 SGLM 会拿旧 anchor 凑一个
            // 偏移出来，刷完停在「顶部偏下」。
            invalidateSpanAssignments()
            scrollToPositionWithOffset(0, 0)
        }
    }

    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    /**
     * 本页看的就是「我的收藏」，所以和 [ceui.pixiv.ui.collection.LikeIllustFeedFragment] 一样
     * 尊重「收藏页隐藏收藏按钮」设置——同一批内容换个入口就多出一排爱心，是前后不一致。
     */
    override val hideLikeButton: Boolean
        get() = SessionManager.loggedInUid == libraryViewModel.shelf.ownerUid &&
                Shaft.sSettings.isHideStarButtonAtMyCollection()

    /**
     * 空态要分清三种「空」，不然用户没法知道该等还是该改条件：
     * 条件筛没了 / 镜像还没补到这个书架 / 是真的一件都没收藏。
     * （`hasAnyCondition` 已经把关键词算在内，不必再单判。）
     */
    override val emptyStateText: CharSequence
        get() = when {
            libraryViewModel.filter.value.hasAnyCondition ->
                getString(R.string.bookmark_library_empty_filtered)
            libraryViewModel.mirrorState.value?.isFirstSyncDone == false ->
                getString(R.string.bookmark_library_empty_syncing)
            else -> getString(R.string.bookmark_library_empty)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _libraryBinding = FragmentBookmarkLibraryBinding.bind(view)
        libraryViewModel.bind(initialShelf)

        setUpToolbar()
        // 「原始收藏列表」是留给「我要看服务端原本的样子」的退路：本页展示的是本地镜像
        // （顺序是本地重排的，内容取的是镜像时冻结的快照）。公开/私人本页已经能直接切，
        // 所以这条退路只为「原序 + 最新」这两点存在。
        libraryBinding.toolbar.menu.add(0, MENU_CLASSIC, 0, R.string.bookmark_library_open_classic)
        libraryBinding.toolbar.menu.add(0, MENU_REBUILD, 1, R.string.bookmark_library_rebuild)
        libraryBinding.toolbar.setOnMenuItemClickListener { item ->
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

        setUpShelfSwitch()
        setUpSearch()
        setUpChips()
        observeState()

        // 打开收藏库本身就是一次「用户在看这个书架」的信号：让引擎马上补一次增量，
        // 而不是等下一个例行窗口。补的过程静默，页面照常用本地数据。
        requireContext().appServices().bookmarkMirror
            .ensureShelf(libraryViewModel.shelf, reason = "打开收藏库")
    }

    override fun onDestroyView() {
        // 用可空引用而不是 libraryBinding：onCreateView 成功但 onViewCreated 中途抛异常时，
        // 系统仍会回调 onDestroyView，那时 _libraryBinding 还是 null，会抛出的 getter 把
        // 真正的异常盖掉。
        pendingSearch?.let { _libraryBinding?.searchInput?.removeCallbacks(it) }
        pendingSearch = null
        sortChip = null
        reverseChip = null
        randomChip = null
        filterChip = null
        clearChip = null
        _libraryBinding = null
        super.onDestroyView()
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
        val binding = libraryBinding
        binding.toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            feedBinding.feedListView.updatePadding(0, 0, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * 公开 / 悄悄收藏切换。点「悄悄收藏」本身就是一次明确的用户意图，所以顺带把那个书架
     * 注册进镜像（[trackBookmarkShelfVisit] 同款的隐私边界：没主动看过就不会去拉）。
     */
    private fun setUpShelfSwitch() {
        val binding = libraryBinding
        binding.shelfPublic.setOnClickListener { switchShelf(MirrorRestrict.PUBLIC) }
        binding.shelfPrivate.setOnClickListener { switchShelf(MirrorRestrict.PRIVATE) }
        renderShelfSwitch()
    }

    private fun switchShelf(restrict: MirrorRestrict) {
        val current = libraryViewModel.shelf
        if (current.restrict == restrict) return
        val next = current.copy(restrict = restrict)
        if (!libraryViewModel.switchShelf(next)) return
        libraryBinding.searchInput.setText("")
        requireContext().appServices().bookmarkMirror
            .ensureShelf(next, reason = "收藏库切换到${next.restrict.apiValue}")
        renderShelfSwitch()
        renderChips()
        applyMirrorState()
        applyFilterChange()
    }

    private fun renderShelfSwitch() {
        val binding = _libraryBinding ?: return
        val isPublic = libraryViewModel.shelf.restrict == MirrorRestrict.PUBLIC
        // 分段按钮的 drawable / 字色选择器认的是 state_selected，不是 activated
        binding.shelfPublic.isSelected = isPublic
        binding.shelfPrivate.isSelected = !isPublic
        // 只出文案，不挂件数：这里是 toolbar，一个「1,008」摆在标题位上既容易被读成总数，
        // 也和下面 chip 行上的「筛选 · N」抢注意力。件数在筛选面板的提交条上（「查看 N 件」）。
    }

    private fun setUpSearch() {
        val input = libraryBinding.searchInput
        input.setText(libraryViewModel.filter.value.keyword)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString().orEmpty()
                pendingSearch?.let { input.removeCallbacks(it) }
                val task = Runnable {
                    if (libraryViewModel.updateFilter { it.copy(keyword = keyword) }) {
                        applyFilterChange()
                    }
                }
                pendingSearch = task
                input.postDelayed(task, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    private fun setUpChips() {
        val row = libraryBinding.chipRow
        row.removeAllViews()

        sortChip = addChip(row, "") { BookmarkFilterSheet.show(this) }
        // 倒序是本页存在的直接理由（#1323），必须是一键，不能埋进面板
        reverseChip = addChip(row, getString(R.string.bookmark_chip_oldest_first)) {
            val next = if (libraryViewModel.filter.value.sort == BookmarkSort.BOOKMARK_OLDEST) {
                BookmarkSort.BOOKMARK_NEWEST
            } else {
                BookmarkSort.BOOKMARK_OLDEST
            }
            if (libraryViewModel.updateFilter { it.copy(sort = next) }) applyFilterChange()
        }
        randomChip = addChip(row, getString(R.string.bookmark_chip_random)) {
            // 已经在随机态时再点 = 重新洗牌，所以种子每次都换
            val alreadyRandom = libraryViewModel.filter.value.sort.isRandom
            val changed = libraryViewModel.updateFilter {
                if (alreadyRandom) {
                    it.copy(randomSeed = System.currentTimeMillis())
                } else {
                    it.copy(sort = BookmarkSort.RANDOM, randomSeed = System.currentTimeMillis())
                }
            }
            if (changed) applyFilterChange()
        }
        filterChip = addChip(row, getString(R.string.bookmark_chip_filter)) {
            BookmarkFilterSheet.show(this)
        }
        clearChip = addChip(row, getString(R.string.bookmark_chip_clear)) {
            libraryBinding.searchInput.setText("")
            if (libraryViewModel.clearConditions()) applyFilterChange()
        }
        renderChips()
    }

    private fun addChip(row: ViewGroup, text: String, onClick: () -> Unit): TextView =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(resources.getColorStateList(R.color.bookmark_chip_text, null))
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
        val filter = libraryViewModel.filter.value
        // 条件被别处清空了（筛选面板里的「清空」），搜索框要跟着空掉。
        // 只做「清空」这一个方向、且只在框里确实还有字时动手：绝不拿 filter 去覆盖用户
        // 正在敲的内容 —— 输入防抖期间 filter 根本不会发射，所以这里也不会和打字打架。
        val input = _libraryBinding?.searchInput
        if (filter.keyword.isEmpty() && input != null && input.text.isNotEmpty()) {
            input.setText("")
        }
        sortChip?.text = getString(sortLabelRes(filter.sort))
        // 排序 chip 只在「不是默认排序」时点亮：默认态点亮一片，选中态就不再是信息了
        sortChip?.isActivated = filter.sort != BookmarkSort.BOOKMARK_NEWEST
        reverseChip?.isActivated = filter.sort == BookmarkSort.BOOKMARK_OLDEST
        randomChip?.isActivated = filter.sort.isRandom

        val conditions = countConditions(filter)
        filterChip?.text = if (conditions > 0) {
            getString(R.string.bookmark_chip_filter_count, conditions)
        } else {
            getString(R.string.bookmark_chip_filter)
        }
        filterChip?.isActivated = conditions > 0
        // 「清空」只在真有东西可清时出现：常驻一个永远灰着的按钮只是噪音
        clearChip?.visibility = if (filter.hasAnyCondition) View.VISIBLE else View.GONE
    }

    /** chip 上那个数字：用户开了几个筛选维度。排序不算——它不减少结果。 */
    private fun countConditions(filter: ceui.pixiv.db.mirror.BookmarkFilter): Int {
        var count = 0
        if (filter.keyword.isNotBlank()) count++
        if (filter.tagNames.isNotEmpty()) count++
        if (filter.excludedTagNames.isNotEmpty()) count++
        if (filter.authorIds.isNotEmpty()) count++
        if (filter.workTypes.isNotEmpty()) count++
        if (filter.orientations.isNotEmpty()) count++
        if (filter.ai != ceui.pixiv.db.mirror.AiFilter.ANY) count++
        if (filter.age != ceui.pixiv.db.mirror.AgeFilter.ANY) count++
        if (filter.pages != ceui.pixiv.db.mirror.PageFilter.ANY) count++
        if (filter.validity != ceui.pixiv.db.mirror.ValidityFilter.ANY) count++
        if (filter.minBookmarks != null || filter.maxBookmarks != null) count++
        if (filter.createdFromMs != null || filter.createdToMs != null) count++
        if (filter.seriesOnly) count++
        return count
    }

    /** 最近一次拿到的全部书架状态；切书架时按当前 shelfKey 重新挑一条出来。 */
    private var latestStates: List<ceui.pixiv.db.mirror.BookmarkMirrorStateEntity> = emptyList()

    private fun observeState() {
        val uid = initialShelf.ownerUid
        val mirror = requireContext().appServices().bookmarkMirror
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    libraryViewModel.filter.collectLatest { renderChips() }
                }

                launch {
                    libraryViewModel.totalCount.collectLatest {
                        // 进度条上的「已 N 件」和标题栏的总数读的是同一个值，
                        // 得一起刷，否则两处数字会差一拍。
                        renderSyncBanner()
                    }
                }
                launch {
                    // 镜像状态：进度条 + 「补齐了就重算计数」。Room 的 Flow 在写入后自动发新值，
                    // 所以后台每写一页，这里的数字就会跟着往上走 —— 用户看得见它在长。
                    mirror.observeState(uid).collectLatest { states ->
                        latestStates = states
                        applyMirrorState()
                    }
                }
                launch {
                    // 按 owner 订阅：页面可以就地切书架，按 shelfKey 订的话每切一次都要重订
                    mirror.observeOwnerCount(uid).collectLatest {
                        libraryViewModel.onMirrorChanged()
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
     * 数「正在补齐 · 已 300 件」。用户看到的是两句互相打脸的话，唯一的出路是自己想到下拉刷新。
     *
     * 只在**列表确实为空**时才重查：有内容的时候后台每 5 秒写一页，跟着刷就等于每 5 秒
     * 把用户的滚动位置拽回顶部一次。而列表非空时本来也不需要它 —— 本地分页每页都是一次
     * 新查询，用户往下滑自然就会读到后来补进去的行。
     *
     * 有筛选条件时也不重查：那种「空」是条件没筛到，不是数据没到，重查一百次还是空。
     */
    private fun refreshIfStillEmpty() {
        if (_libraryBinding == null) return
        // 只在**镜像还没补完**时才管。少了这道门就会每次进页面都白查一遍：Room 的 Flow
        // 一订阅就先发一次当前值，而那一刻首屏查询还没提交进 uiState，列表当然是空的，
        // 于是判定「空」→ 再刷一次 → 同一条查询跑两遍（真机日志里 2ms 内连着两次）。
        // 补完之后本来也不需要它：列表非空，往下滑每页都是新查询，自然读得到后来补的行。
        if (libraryViewModel.mirrorState.value?.isFirstSyncDone != false) return
        if (libraryViewModel.filter.value.hasAnyCondition) return
        if (currentIllustItems().isNotEmpty()) return
        Timber.tag(TAG).d("镜像仍在补齐而列表为空，自动重查")
        applyFilterChange()
    }

    /** 从最近一份状态列表里挑出**当前**书架那条，喂给 VM 并重画进度条。 */
    private fun applyMirrorState() {
        val key = libraryViewModel.shelf.key
        libraryViewModel.setMirrorState(latestStates.firstOrNull { it.shelfKey == key })
        renderSyncBanner()
    }

    private fun renderSyncBanner() {
        val binding = _libraryBinding ?: return
        val state = libraryViewModel.mirrorState.value
        val total = libraryViewModel.totalCount.value ?: 0
        // 补齐过一次之后这条就永远不再出现 —— 「同步完成过一次，以后只维护」的界面表达。
        val syncing = state != null && !state.isFirstSyncDone
        binding.syncBanner.visibility = if (syncing) View.VISIBLE else View.GONE
        if (!syncing || state == null) return
        binding.syncText.text = when {
            state.cooldownUntil > System.currentTimeMillis() ->
                getString(R.string.bookmark_library_sync_cooldown)
            state.phase == MirrorPhase.BACKFILLING ->
                getString(R.string.bookmark_library_syncing, formatCount(total))
            else -> getString(R.string.bookmark_library_sync_queued)
        }
    }

    /**
     * 打开原始的双 tab 收藏页。带 [Params.FLAG] 标记，那边据此**不再**把入口重定向回本页，
     * 否则用户从本页点进去会被立刻弹回来，两个页面互相踢皮球。
     */
    private fun openClassicCollection() {
        startActivity(
            Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.MY_ILLUST_COLLECTION.key)
                putExtra(Params.FLAG, true)
            }
        )
    }

    private fun rebuildMirror() {
        val shelf = libraryViewModel.shelf
        Timber.tag(TAG).i("用户手动重建镜像 %s", shelf.label)
        requireContext().appServices().bookmarkMirror.rebuildShelf(shelf)
        applyFilterChange()
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

        @JvmStatic
        @JvmOverloads
        fun newInstance(
            userId: Long = SessionManager.loggedInUid,
            starType: String = Params.TYPE_PUBLIC,
            contentType: MirrorContentType = MirrorContentType.ILLUST,
        ): BookmarkLibraryFragment = BookmarkLibraryFragment().apply {
            arguments = Bundle().apply {
                putLong(Params.USER_ID, userId)
                putString(Params.STAR_TYPE, starType)
                putInt(ARG_CONTENT_TYPE, contentType.code)
            }
        }
    }
}
