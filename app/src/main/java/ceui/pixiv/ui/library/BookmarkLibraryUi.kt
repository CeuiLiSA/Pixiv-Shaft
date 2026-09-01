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
    /** 当前列表上有多少条目。判断「屏幕上这份是不是已经过期」要用，见 [refreshIfStale]。 */
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

    /**
     * 上一次看到的库内行数，用来认出「库里多出了东西」。
     *
     * 它是**当前这个书架**的行数，所以换书架时必须清零（见 [switchShelf]）：不清的话，
     * 新书架的第一次计数会被当成「多出来的东西」——从 31 行的悄悄收藏切到 1012 行的公开
     * 收藏，看起来就是「凭空多了 981 条」，于是刚切完立刻又重查一遍。
     */
    private var lastKnownStored: Int? = null

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

        setUpPullToRefresh()

        // 打开收藏库本身就是一次「用户在看这个书架」的信号：让引擎马上补一次增量，
        // 而不是等下一个例行窗口。补的过程静默，页面照常用本地数据。
        context.appServices().bookmarkMirror.ensureShelf(viewModel.shelf, reason = "打开收藏库")
    }

    /**
     * 页面重新回到前台。**必须在 onResume 上再对一次**，不能只在 onViewCreated：
     * 「在网页端收藏几张 → 切回 app 看看」是最典型的动作，而那个来回根本不会销毁本页的
     * view —— 只挂在 install 上的话，回来看到的还是走之前那份，用户的结论就是
     * 「网页端收藏的东西 app 里看不到」。
     */
    fun onResumed() {
        if (destroyed) return
        context.appServices().bookmarkMirror.ensureShelf(viewModel.shelf, reason = "回到收藏库")
    }

    /**
     * 下拉刷新 = **去服务端对一次表头** + 重查本地。
     *
     * 框架默认只做后者（`feedViewModel.refresh()`），对本页来说等于什么都没做：本地源
     * 读的就是镜像表，镜像不动，再查一百次也还是同一批。而「下拉刷新」恰恰是用户想说
     * 「我在别处收藏了东西，去看看」时唯一会做的动作 —— 它必须真的去同步，否则这套
     * 镜像对「网页端/别的设备上的收藏」就是个死胡同。
     *
     * 同步是异步的（受全局限速，约 10 秒两页），所以下拉的转圈会先随本地重查停下；
     * 新收藏落库后由 [refreshIfStale] 接手上屏。
     */
    private fun setUpPullToRefresh() {
        val refreshLayout = binding.feedRoot.feedRefreshLayout
        refreshLayout.setOnRefreshListener {
            context.appServices().bookmarkMirror.syncNow(viewModel.shelf, reason = "下拉刷新")
            applyFilterChange()
        }
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
        // **刻意不在这里刷新**：清空发生在 rebuildShelf 自己的协程里，这里立刻刷只会
        // 读到清空前的数据（真机复现过：清空 2 行之后 2ms，那次查询读到的还是 2 行）。
        // 交给 [refreshIfStale] —— 清空落库后 totalCount 会掉到 0，它自然会把屏幕对齐，
        // 回填补进来之后再对齐一次。少一次抢跑的刷新，也就少一个需要兜的时序。
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
        // 行数基准跟着书架走：换了书架，上一个书架的行数就不再是比较的参照物
        lastKnownStored = null
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
                    // 进度条上的「已 N 件」跟着镜像行数走；顺带在这里判「屏幕上的内容是不是
                    // 已经过期」——必须挂在 totalCount 上而不是 observeOwnerCount 上，因为
                    // 后者发射时 refreshCounts 才刚启动，读到的还是旧计数。
                    viewModel.totalCount.collectLatest {
                        renderSyncBanner()
                        refreshIfStale()
                    }
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
                    }
                }
            }
        }
        // 掉网 / 恢复联网都要重画进度条：文案里「正在补齐 / 当前无网络」这两句的真假只取决于
        // 网络，而网络变化不会引起镜像表或状态表的任何写入 —— 不单独看着它，用户掉线后
        // 那句「正在后台补齐」会一直挂在那儿骗人，直到别的事件碰巧触发一次重绘。
        context.appServices().networkStateManager.networkState
            .observe(fragment.viewLifecycleOwner) { renderSyncBanner() }
    }

    /**
     * 屏幕上这份列表和库里对不上了 → 自动重查一次。
     *
     * 判据刻意只是**两边条数的对账**，不掺「镜像同步到哪一步了」：
     *
     * - `库里 < 屏幕`：屏幕上挂着库里已经没有的行。最典型的是刚点了「重建本地镜像」——
     *   `rebuildShelf` 是 fire-and-forget 的，清空在它自己的协程里，而 `rebuildMirror`
     *   紧接着发的那次刷新完全可能跑在清空**之前**（真机日志复现：清空 2 行之后 2ms，
     *   那次查询读到的还是 2 行）。
     * - `屏幕为空而库里有货`：本地源查到 0 行时返回的 `nextCursor` 是 null，feeds 框架据此
     *   判定「到底了」，从此不再问数据源要任何东西。所以镜像补进第一批之后，得有人推它一把。
     *
     * **不要**再拿 `isFirstSyncDone` 当条件（上一版就栽在这儿）：它恰好在最后一页落库的
     * 同一时刻翻成 true，于是「补齐中且空」这条判据在最需要它的那一瞬间失效 —— 回填明明
     * 完成了，页面却永远停在「正在补齐…」的空态上（真机复现）。条数对账没有这个时序缝隙。
     *
     * 只在两边真的对不上时动手，所以取消收藏删掉一两行**不会**触发（1007 < 60 不成立），
     * 用户的滚动位置不会被无谓地拽回顶部。有筛选条件时一律不管：那种「空」是条件没筛到。
     */
    private fun refreshIfStale() {
        if (destroyed) return
        // 已经有一次刷新在路上（换书架 / 换筛选刚发出去的那次）：此刻屏幕上本来就是旧的，
        // 而且正在被修。拿这份必然过期的 shown 去和新库比，只会得出一个恒真的结论，然后
        // 再排一次多余的刷新——真机实测「切到更小的书架」每次都因此连查两遍。
        if (resetAfterGeneration != null) return
        if (viewModel.filter.value.hasAnyCondition) return
        val shown = itemCount()
        val stored = viewModel.totalCount.value ?: return
        val previous = lastKnownStored
        lastKnownStored = stored

        val hasGhostRows = stored < shown
        val emptyButStored = shown == 0 && stored > 0
        // 库里多出了东西，而用户正停在列表顶部 → 直接让它上屏。
        // 三个条件缺一不可：
        // - **多出来**（而不是变少）：变少是取消收藏，那条由 hasGhostRows 管；
        // - **停在顶部**：默认排序下新收藏就排在最上面，用户正看着那儿，插进去是他期待的；
        //   滚到下面时一律不动 —— 把正在浏览的人拽回顶部比晚看到几条糟得多；
        // - **已经补齐过一次**：首次回填期新行是从新往旧一路往**末尾**加的，顶部根本不会变，
        //   跟着刷只是每 5 秒把整个列表重排一遍的无用功。
        val grewWhileAtTop = previous != null &&
            stored > previous &&
            viewModel.mirrorState.value?.isFirstSyncDone == true &&
            !listView.canScrollVertically(-1)

        if (!hasGhostRows && !emptyButStored && !grewWhileAtTop) return
        Timber.tag(TAG).d(
            "列表与库对不上，自动重查（库内 %d 行 / 屏幕 %d 条，新增上屏=%b）",
            stored, shown, grewWhileAtTop,
        )
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
        // 离线时引擎每个 tick 都直接返回 Idle（连库都不查），一页都不会补。
        // 这时候还挂着「正在后台补齐」就是在骗人：用户会以为等一会儿就好，实际要等到有网。
        val offline = context.appServices().networkStateManager.networkState.value?.isOnline != true
        binding.syncText.text = when {
            offline -> context.getString(R.string.bookmark_library_sync_offline)
            state.cooldownUntil > System.currentTimeMillis() ->
                context.getString(R.string.bookmark_library_sync_cooldown)
            state.phase == MirrorPhase.BACKFILLING ->
                context.getString(R.string.bookmark_library_syncing, formatCount(total))
            else -> context.getString(R.string.bookmark_library_sync_queued)
        }
        // 转圈只在真的在补的时候转；离线/冷却时停下来，别让一个永远转着的圈暗示「马上就好」
        binding.syncSpinner.visibility =
            if (offline || state.cooldownUntil > System.currentTimeMillis()) View.INVISIBLE else View.VISIBLE
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
