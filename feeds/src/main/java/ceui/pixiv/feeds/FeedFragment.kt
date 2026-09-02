package ceui.pixiv.feeds

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.annotation.LayoutRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import ceui.pixiv.feeds.databinding.FragmentFeedBinding
import kotlinx.coroutines.launch

/**
 * 列表页基类。子类只声明两件事：数据从哪来（[feedViewModel]）、每种条目怎么画（[onCreateRenderers]），
 * 刷新 / 翻页 / 空态 / 错误态 / 防重入全部由框架负责。
 *
 * ```
 * class XxxFragment : FeedFragment() {
 *     override val feedViewModel by feedViewModels { XxxFeedSource() }
 *     override fun onCreateRenderers() = listOf(xxxRenderer(), yyyRenderer())
 * }
 * ```
 *
 * ⚠️ 数据源 lambda（initialFetch / mapper）不要捕获 Fragment——它归 VM 长期持有，
 * 会把旋转前的旧 Fragment 实例钉在内存里（零捕获约定详见 [feedViewModels] 文档）。
 *
 * 需要 toolbar 等额外骨架时传自定义 [contentLayoutId]，布局里
 * `<include layout="@layout/fragment_feed"/>`（id 保持 feed_root）即可。
 */
abstract class FeedFragment(
    @LayoutRes contentLayoutId: Int = R.layout.fragment_feed,
) : Fragment(contentLayoutId) {

    protected abstract val feedViewModel: FeedViewModel<*>

    /** 每种条目类型一个 Renderer；只在 onViewCreated 时调用一次。 */
    protected abstract fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>>

    /**
     * 裸 fragment_feed 形态下,是否给列表底部补 inset。铺到屏幕底的列表覆写为 true;
     * 只占半屏、上面还压着别的内容的货架(横向 rail 等)保持 false。
     * 具体补多少由宿主分发的 inset 决定:一般宿主是手势条/导航栏,首页是浮在内容之上、会跟随
     * 滚动收起的底栏高度(MainActivity 重写了分发给内容区的 inset)。
     * 带 toolbar 的骨架不受此开关影响(setUpToolbar 自理)。
     */
    protected open val applyBottomSafeInset: Boolean = false

    /**
     * 滚到底是否自动追加下一页。货架类（只展示第一页的横向 rail）覆写为 false：
     * 数据源照常带回真实 nextCursor（[FeedViewModel.currentCursor] 仍可交接给「查看更多」整页续读），
     * 只是本列表自己不再往后翻——对齐 legacy 那些 `initNextApi=null` + `setEnableLoadMore(false)` 的货架。
     */
    protected open val loadMoreEnabled: Boolean = true

    /**
     * 下拉刷新的**静态**开关（装配列表时读一次）。与 [loadMoreEnabled] 对称：横向 rail / 嵌在
     * 别人滚动容器里的货架覆写为 false（它们的手势归宿主，自己吃掉下拉会打架）。
     *
     * 运行时要开关（如进入本地过滤态时临时禁用下拉）用 [setRefreshEnabled]，别伸手进
     * `feedBinding.feedRefreshLayout`。
     */
    protected open val refreshEnabled: Boolean = true

    /**
     * 运行时开关下拉刷新。用于「页面进入某种临时态就不该整页重刷」的场景——收藏标签页进入
     * 本地过滤态就是（列表此刻显示的是筛选结果，下拉刷新会把它整代换掉）。
     *
     * 有这个方法之前，唯一需要它的页面只能伸手进 `feedBinding.feedRefreshLayout.isEnabled = ...`：
     * 那是绕过框架直操内部 binding 的唯一一处，正是「缺了运行时钩子」逼出来的（静态的
     * [refreshEnabled] 只在装配时读一次，表达不了临时态）。view 未创建时安全 no-op。
     *
     * ⚠️ 不改 [refreshEnabled] 的取值：[rebuildList] 重装列表会按静态值回到默认，
     * 临时态的开关归调用方自己在重装后重新表达。
     */
    protected fun setRefreshEnabled(enabled: Boolean) {
        _binding?.feedRefreshLayout?.isEnabled = enabled
    }

    private var _binding: FragmentFeedBinding? = null
    protected val feedBinding: FragmentFeedBinding
        get() = checkNotNull(_binding) { "view 尚未创建或已销毁" }

    protected var feedAdapter: FeedAdapter? = null
        private set

    /** 首屏是否用瀑布流骨架图（否则 fallback 转圈圈）。在 onViewCreated 按布局定，render 只读它。 */
    private var skeletonEnabled: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val feedRoot = requireNotNull(view.findViewById<View>(R.id.feed_root)) {
            "自定义布局必须 <include layout=\"@layout/fragment_feed\"/>（id 保持 feed_root）"
        }
        val binding = FragmentFeedBinding.bind(feedRoot)
        _binding = binding

        // 裸 fragment_feed 自带列表底色，否则宿主布局的装饰背景（如 activity_multi_view_pager
        // 根上的 ?attr/colorPrimary）会从透明列表后面整页透出来；自定义 contentLayoutId 的页面
        //（fragment_toolbar_feed 等）背景归自己的根布局管，这里不越权覆盖。
        if (view === feedRoot) {
            feedRoot.setBackgroundColor(feedRootBackgroundColor)
        }

        // 底部 safe-area:裸 fragment_feed 铺到屏幕底,列表末尾会被手势条/导航栏(首页则是浮在内容
        // 之上的底栏)挡住。给列表补底部 systemBars inset(clipToPadding=false 已使末条能上滚)。
        // 只在裸 feed 形态生效:带 toolbar 的骨架由 setUpToolbar 自己吃 inset,重复套会多留一截。
        if (view === feedRoot && applyBottomSafeInset) {
            val listView = binding.feedListView
            val basePaddingBottom = listView.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(listView) { v, windowInsets ->
                val bottom = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                v.updatePadding(bottom = basePaddingBottom + bottom)
                windowInsets
            }
            ViewCompat.requestApplyInsets(listView)
        }

        installList(binding)

        // 下拉刷新转圈 + 空态插画共用一份宿主配色（见 [FeedHost.theme]）。切主题/日夜都会重建
        // Activity → 走到这里重算，不用自己监听。
        val theme = feedTheme

        // 下拉刷新转圈：SwipeRefreshLayout 默认是 Material 蓝箭头 + #FAFAFA 近白底盘，既不跟主题
        // 也不跟日夜（这两个颜色 XML 设不了，只能在这里设，所以收口在基类——6 个 feed 布局都是
        // <include fragment_feed/>，共用同一个 feed_refresh_layout）。
        // 箭头取 accent，对齐宿主刷新头的既有取色惯例；底盘取 spinnerTrack（「隐约带主题色的
        // 不透明悬浮底」，语义正是这块浮起的小圆饼），不换的话暗色模式下是块白饼。
        // 宿主要保证两者日夜两支恒为「浅箭头深底 / 深箭头浅底」，对比度不会塌。
        binding.feedRefreshLayout.setColorSchemeColors(theme.accent)
        binding.feedRefreshLayout.setProgressBackgroundColorSchemeColor(theme.spinnerTrack)
        binding.feedRefreshLayout.isEnabled = refreshEnabled
        binding.feedRefreshLayout.setOnRefreshListener { feedViewModel.refresh() }
        binding.feedStateText.setOnClickListener { feedViewModel.refresh() }

        // 空态/错误态插画：两张图都是灰色描边，这里用宿主强调色 tint。accent 应当是
        // readability-adjusted 的主题色（深色→提亮、浅色→压深），比裸 ?attr/colorPrimary 柔和
        // 且日夜双模都可读；再压 60% alpha（SRC_IN）让它像插画而非实心色块。
        binding.feedEmptyImage.imageTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(theme.accent, EMPTY_IMAGE_ALPHA))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collect { state -> render(state) }
            }
        }

        // 网络恢复自动重试。「断网→有网」的迁移判定归宿主（[FeedHost.observeNetworkRestored]），
        // 本模块不认识任何网络状态实现；具体重试什么见 [onNetworkRestored]。
        FeedFramework.host.observeNetworkRestored(this) { onNetworkRestored() }
    }

    /**
     * 断网 → 有网的迁移回调（不含 observe 注册时的粘性首发）。
     *
     * 基类默认重试用户本来就无事可做的错误态：全屏错误整页重刷、追加错误续上 footer，两者幂等
     * 且不打扰。「有内容兜底的刷新失败」刻意不自动 refresh：整代替换会清表回顶，把正在浏览缓存
     * 内容的用户从半路拽走。
     *
     * 页面自己还有别的东西要在联网后补拉（如详情页那些懒加载区块，它们的触发信号只有 attach，
     * holder 停在屏幕内就再也不会重试）时覆写本方法，**记得调 super**。
     */
    protected open fun onNetworkRestored() {
        val state = feedViewModel.uiState.value
        when {
            state.showFullscreenError -> feedViewModel.refresh()
            state.append is LoadState.Error -> feedViewModel.retryAppend()
        }
    }

    /**
     * 装配列表：Renderer / Adapter / LayoutManager / 骨架图。[onViewCreated] 装一次，
     * [rebuildList] 在运行时换列表形态时重装。
     */
    private fun installList(binding: FragmentFeedBinding) {
        val listView = binding.feedListView
        // 重装时先卸掉上一轮留下的东西：decoration 会一层层叠加，骨架会重复堆在容器里
        while (listView.itemDecorationCount > 0) {
            listView.removeItemDecorationAt(0)
        }
        binding.feedSkeleton.removeAllViews()

        val adapter = FeedAdapter(
            renderers = onCreateRenderers() + AppendFooterRenderer(
                onRetry = { feedViewModel.retryAppend() },
                onContinue = { feedViewModel.continueAppend() },
            ),
            onNearEnd = { if (loadMoreEnabled) feedViewModel.loadMore() },
        )
        feedAdapter = adapter

        val layoutManager = onCreateLayoutManager()
        // 首屏骨架图：按布局挑一种（瀑布流 / 竖向小说卡），null = 本页不用骨架 → render 里走转圈圈。
        val skeleton = onCreateSkeletonView(layoutManager)
        skeletonEnabled = skeleton != null
        if (skeleton != null) {
            binding.feedSkeleton.addView(
                skeleton,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        listView.layoutManager = layoutManager
        // 必须是 setAdapter 而不是 swapAdapter：重装时 viewType 的含义会整个换掉（时间线卡和
        // 瀑布流卡都是 0 号），setAdapter 的 compatibleWithPrevious=false 会让 RecyclerView 清掉
        // 旧 pool，否则上一种卡的 ViewHolder 会被派给新 Renderer 当场 ClassCastException。
        // ⚠️ 由此推论：[onListReady] 里不要 setRecycledViewPool 装共享池——共享池 attachCount != 0
        // 时 setAdapter 不清它，[rebuildList] 就会串类型。
        listView.adapter = adapter
        onListReady(listView)
        // 必须记在 onListReady 之后：有的页面在那里主动 itemAnimator = null（历史页/事件页），
        // 记早了换代结束会把 DefaultItemAnimator 装回它们身上，等于替它们把动画又打开。
        listItemAnimator = listView.itemAnimator
    }

    /**
     * 运行时重装列表：换一套 Renderer / LayoutManager，**数据留在 VM 里不重拉**
     * （用于「同一份数据、两种排布」的页面，如动态页的时间线 ↔ 瀑布流切换）。
     *
     * 重装后当前 [FeedUiState] 会立刻重新 render 一遍，不必等下一次数据变化。
     * 与 legacy 换 adapter 的语义一致：滚动位置会回到顶部。view 未创建时安全 no-op。
     */
    protected fun rebuildList() {
        val binding = _binding ?: return
        installList(binding)
        render(feedViewModel.uiState.value)
    }

    override fun onResume() {
        super.onResume()
        // 懒加载 VM（feedViewModels(autoLoad = false)）的首屏在这里补：
        // 只有真正可见的 tab 会走到 RESUMED（宿主 pager 需 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT），
        // ensureLoaded 幂等，失败后再次可见自动重试
        if (!feedViewModel.autoLoad) {
            feedViewModel.ensureLoaded()
        }
    }

    override fun onDestroyView() {
        feedAdapter = null
        _binding = null
        super.onDestroyView()
    }

    /** 宿主注入的配色（见 [FeedHost.theme]）。现取不缓存：主题 / 日夜切换会重建 Activity。 */
    protected val feedTheme: FeedTheme
        get() = FeedFramework.host.theme(requireContext())

    /**
     * 裸 fragment_feed（没给自定义 contentLayoutId 的页面）刷的列表底色。
     *
     * 默认取宿主配色的 [FeedTheme.rootBackground] —— 它应当就是宿主各页的统一底色。以前这里
     * 写死 legacy 的 fragment_center（夜间 #2A2A2A），比宿主 V3 底（夜间 #08080C）浅一大截，
     * 结果是：宿主布局明明写了 v3_bg（如动态页的 user_recmd_fragment），列表一 attach 就被这层
     * 基底盖成灰块，糊出一条撞色横带。PivisionFeedFragment / FollowingIllustFeedFragment
     * 都为此在 onViewCreated 里手动回刷过一遍——那类补丁现在改成覆写本属性即可。
     *
     * 是 @ColorInt 而不是 @ColorRes：嵌在主题派生色宿主里的列表（如动态页那张 sheet 用宿主
     * 算出来的 cardFill）要的是运行时算出来的色值，色值资源表达不了。
     */
    @get:ColorInt
    protected open val feedRootBackgroundColor: Int
        get() = feedTheme.rootBackground

    /**
     * 空列表时的文案，默认是通用的「居然啥也没有」。页面语义更具体时覆写
     *（如「稍后再看列表是空的」）。错误态文案不走这里——那条是框架统一的「点击重试」。
     */
    protected open val emptyStateText: CharSequence
        get() = getString(R.string.empty_list_1)

    /**
     * 空态下可选的动作按钮：`标题 to 点击动作`，返回 null 就只显示文案（默认）。
     *
     * 给那些「空是有原因、而且原因能一键去修」的页面用——光把原因写成一段话、再让用户自己
     * 去菜单里翻入口，等于把人堵在死胡同。只在空态显示；全屏错误态由框架按
     * [FeedHost.shouldSuggestNetworkTest] 决定是否显示「去网络测试」。
     */
    protected open val emptyStateAction: Pair<CharSequence, () -> Unit>?
        get() = null

    /** 默认竖排线性；网格用 [gridLayoutManager]，瀑布流直接返回 StaggeredGridLayoutManager。 */
    protected open fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        return LinearLayoutManager(requireContext())
    }

    /** 列表就绪回调：加 ItemDecoration、共享 RecycledViewPool 等。 */
    protected open fun onListReady(listView: RecyclerView) {}

    /**
     * 首屏加载态画哪种骨架图；返回 null = 本页不用骨架，fallback 转圈圈。
     *
     * 默认只认瀑布流（[StaggeredGridLayoutManager]，列数跟随用户「每行几列」设置）——骨架必须
     * 长得像自己那张卡，卡形状不一样的列表各自覆写（如 `:app` 的 `NovelFeedFragment`
     * 给竖向小说卡）。返回的 View 由基类塞进 feed_skeleton 容器并随视图销毁。
     */
    protected open fun onCreateSkeletonView(
        layoutManager: RecyclerView.LayoutManager,
    ): FeedSkeletonView? {
        return if (layoutManager is StaggeredGridLayoutManager) {
            FeedStaggeredSkeletonView(requireContext()).apply { spanCount = layoutManager.spanCount }
        } else {
            null
        }
    }

    /** 屏幕上有内容时刷新失败的提示，默认轻提示出人话文案；子类可覆盖。 */
    protected open fun onRefreshFailedWithContent(throwable: Throwable) {
        val context = requireContext()
        FeedFramework.host.showMessage(
            context,
            humanReadableErrorOf(throwable) ?: getString(R.string.list_load_failed_tap_retry),
        )
    }

    /**
     * 异常 → 人话，走宿主统一的映射（[FeedHost.humanReadableError]：服务端 user_message 优先，
     * 断网 / 超时 / SSL / 反序列化分档取本地化文案）。宿主没装 / 映射自身出岔子（HttpException
     * 错误体已被上一次渲染消费掉等）/ 映射出空白时返回 null，调用方退回框架通用文案——错误提示
     * 这条路上不允许再抛出第二个异常，所以宿主实现即便违约抛了也在这里被吃掉。
     */
    protected fun humanReadableErrorOf(throwable: Throwable): String? =
        runCatching { FeedFramework.host.humanReadableError(requireContext(), throwable) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** 回到列表顶部（tab 双击、toolbar 点击等场景）。view 未创建时安全 no-op。 */
    fun scrollToTop() {
        _binding?.feedListView?.smoothScrollToPosition(0)
    }

    /** 回顶 + 重新刷新（底栏当前 tab 再点等场景，对齐 legacy ListFragment.forceRefresh）。 */
    fun forceRefresh() {
        // 宿主（FragmentLeft/FragmentHolder 等）视图重建时会重新 new 一份 fragments 数组，
        // 而 pager 复用的是 FragmentManager 恢复的旧实例——数组里这份孤儿实例永不 attach。
        // 无 view 时安全 no-op：孤儿实例上碰 feedViewModel 会在取 ViewModelStore 时抛 ISE
        if (_binding == null) return
        scrollToTop()
        feedViewModel.refresh()
    }

    /** 已自动接好 [FeedRenderer.spanSize] 的 GridLayoutManager。 */
    protected fun gridLayoutManager(spanCount: Int): GridLayoutManager {
        return GridLayoutManager(requireContext(), spanCount).also { manager ->
            manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return feedAdapter?.spanSizeAt(position, spanCount) ?: 1
                }
            }
        }
    }

    /** [state.items] 的 diff 已经全部提交给 RecyclerView 之后回调——此时 adapter 的
     * itemCount/内容才真正反映这份 state，[RecyclerView.findViewHolderForAdapterPosition]
     * 才可能对新插入的条目命中。diff 本身在后台线程算，submitList 调用完立刻查 ViewHolder
     * 大概率还是旧数据（构造函数级的时间竞争）；需要「精确感知新条目已经上屏」的场景
     * （如新评论发出后滚回顶部高亮）在子类覆写，而不是自己拍延迟猜时机。默认 no-op。 */
    protected open fun onListCommitted(state: FeedUiState) {}

    /** 上一次 render 提交过的整代代号，用于识别「这次是换了一代」。随 view 重建归零（那时 adapter 也是空的）。 */
    private var lastRenderedGeneration: Int = 0

    /** [installList] 记下的 itemAnimator 真值（含子类在 onListReady 里关掉动画的情形）。 */
    private var listItemAnimator: RecyclerView.ItemAnimator? = null


    /**
     * 提交一整代新内容：先同步清空再整段插入，**不让 DiffUtil 参与**。
     *
     * `submitList(null)` 会走 AsyncListDiffer 的同步快路（整段 remove），紧接着对空表
     * `submitList(新表)` 也是同步快路（整段 insert）—— 两次都不排后台 diff、不产生 move。
     * 跨代 diff 的害处见 [FeedUiState.refreshGeneration]。
     *
     * 这一段还必须关掉 itemAnimator，理由有两层：
     * - 观感：整段 remove + 整段 insert 会被 DefaultItemAnimator 演成一轮淡出淡入，而全新卡还得
     *   等 Glide 出图，动画只会把「有的秒显、有的后到」放得更明显。整代替换的正确观感是
     *   「内容就地换掉」，不是表演。
     * - 稳定性：整段增删 + SGLM 的 predictive animation 正是 `:app` 的 `StaggeredManager`
     *   专门吞 IndexOutOfBounds 的那个组合，没必要在这条路上惹它。
     *
     * ⚠️ 恢复必须 post 到下一帧，不能在 submitList 的回调里就地恢复：RecyclerView 是在
     * **layout 时**才读 mItemAnimator 决定播不播动画的，而不是在 notifyItemRange* 那一刻；
     * 上面两次提交都走同步快路，回调在本帧内就跑完了，就地恢复等于什么都没关掉。
     * post 的这一帧里点赞恰好落地也只是少一次局部动画，不影响正确性。
     */
    private fun commitNewGeneration(
        adapter: FeedAdapter,
        displayList: List<FeedItem>,
        state: FeedUiState,
    ) {
        val listView = feedBinding.feedListView
        // 恢复值取 installList 记下的真值，**不要**在这里 `val a = listView.itemAnimator` 捕获后再装回：
        // 那样两次换代若落在同一帧内（恢复的 post 还没跑），第二次捕获到的就是第一次摘空后的 null，
        // 它的 post 会把 animator 永久设成 null。取真值则重入多少次都幂等。
        listView.itemAnimator = null
        adapter.submitList(null)
        adapter.submitList(displayList) {
            resetToTop(listView)
            // view 可能在这一帧后就销毁；listView 是捕获的强引用，post 照常跑，只是往一个已 detach
            // 的列表上设回 animator——无害且不泄漏（listView 随 view 一起回收）。
            listView.post { listView.itemAnimator = listItemAnimator }
            afterListCommitted(state)
        }
    }

    /**
     * 两条提交路径（DiffUtil / 整代清空重填）的共同收口：先回调子类，再补一次触底预取检查。
     */
    private fun afterListCommitted(state: FeedUiState) {
        onListCommitted(state)
        rearmPaginationIfNearEnd()
    }

    /**
     * 列表提交后补一次「触底预取」检查。
     *
     * 常规翻页完全由 [FeedAdapter] 的 onBind 点火。但刷新把翻过多页的长列表换成一个**短前缀**
     * （首页被 mapper 过滤后只剩几条）时，走的是 DiffUtil 路径：只派发尾部 remove，顶部幸存的
     * holder 内容没变**不会重新 bind**，短列表又填不满屏、滚不动，onNearEnd 从此不再有机会
     * 点火——分页就这样被静默吃掉（PR #1059 真机复现）。这里用与 onBind 相同的判据
     * （[FeedAdapter.isNearEnd]）看一眼当前最后可见位置，已在预取区就主动补一次 loadMore。
     *
     * 时机：提交回调里 RecyclerView 刚 requestLayout、还没排版，此时读到的是旧布局；等这次
     * layout 结束再查（[doOnNextLayout]）。没有 pending layout（diff 零派发）就直接查。
     * 重复触发无害：[FeedViewModel.loadMore] 自带 reachedEnd / 刷新中 / 单飞守卫。
     */
    private fun rearmPaginationIfNearEnd() {
        if (!loadMoreEnabled) return
        val listView = _binding?.feedListView ?: return
        if (listView.isLayoutRequested) {
            listView.doOnNextLayout { checkNearEndNow() }
        } else {
            checkNearEndNow()
        }
    }

    private fun checkNearEndNow() {
        // adapter 现取：rebuildList 可能已经换过一轮，别拿提交时那个
        val adapter = feedAdapter ?: return
        val listView = _binding?.feedListView ?: return
        if (adapter.itemCount == 0) return
        val lastVisible = when (val manager = listView.layoutManager) {
            is StaggeredGridLayoutManager -> manager.findLastVisibleItemPositions(null).max()
            is LinearLayoutManager -> manager.findLastVisibleItemPosition()
            else -> return
        }
        if (lastVisible != RecyclerView.NO_POSITION && adapter.isNearEnd(lastVisible)) {
            feedViewModel.loadMore()
        }
    }

    /**
     * 把列表确定性地拨回顶部，并清掉 LayoutManager 的跨代残留。
     *
     * 清空→重填**不会**让 [StaggeredGridLayoutManager] 忘掉上一代的状态，两样都得手动清，
     * 少一样就是一个用户看得见的 bug：
     * - span 偏移 / lookup 是上一代的残留：某一列以为自己已经填到某个 y，新一代首屏那一列
     *   顶部就空出一大块黑的。[StaggeredGridLayoutManager.invalidateSpanAssignments] 是唯一
     *   能清掉它的公开 API。
     * - `scrollToPosition(0)` 只给位置、不给偏移，SGLM 会拿旧 anchor 去凑出一个偏移 → 刷完
     *   停在「顶部偏下」而不是顶部。必须用 `scrollToPositionWithOffset(0, 0)` 把偏移也定死。
     *
     * （[androidx.recyclerview.widget.GridLayoutManager] 继承自 [LinearLayoutManager]，走同一支。）
     */
    private fun resetToTop(listView: RecyclerView) {
        when (val manager = listView.layoutManager) {
            is StaggeredGridLayoutManager -> {
                manager.invalidateSpanAssignments()
                manager.scrollToPositionWithOffset(0, 0)
            }

            is LinearLayoutManager -> manager.scrollToPositionWithOffset(0, 0)
            else -> manager?.scrollToPosition(0)
        }
    }

    /** adapter 现取不缓存：[rebuildList] 换过 adapter 之后，长活的 uiState collector 不能再往旧的提交。 */
    private fun render(state: FeedUiState) {
        val adapter = feedAdapter ?: return
        val displayList = when {
            state.append is LoadState.Loading || state.append is LoadState.Error ->
                state.items + AppendFooterItem(state.append)
            // 自动翻页预算用完：还有下一页，footer 变「点击加载更多」等用户点
            state.appendPaused -> state.items + AppendFooterItem(state.append, paused = true)
            else -> state.items
        }
        // 整代替换（下拉刷新 / 冷启的缓存→网络）**且新旧两代真的会撕**时才绕开 DiffUtil，
        // 见 [FeedUiState.refreshGeneration] 和 [wouldScrambleAcrossGenerations]。
        // adapter 还空着（首屏、旋转重建、rebuildList 换过 adapter）时没有「旧代」可撕。
        val newGeneration = state.refreshGeneration != lastRenderedGeneration
        lastRenderedGeneration = state.refreshGeneration
        if (newGeneration && adapter.itemCount > 0 && displayList.isNotEmpty() &&
            needsCleanSwapAcrossGenerations(adapter.currentList, displayList)
        ) {
            commitNewGeneration(adapter, displayList, state)
        } else {
            adapter.submitList(displayList) { afterListCommitted(state) }
        }

        val binding = feedBinding
        binding.feedRefreshLayout.isRefreshing =
            state.refresh is LoadState.Loading && state.hasLoadedOnce

        // 首屏加载：瀑布流 → 骨架图，其它 → 转圈圈。骨架 View 靠自身 isShown 自管 shimmer 动画。
        val showSkeleton = skeletonEnabled && state.showFullscreenLoading
        binding.feedSkeleton.isVisible = showSkeleton
        val showSpinner = state.showFullscreenLoading && !showSkeleton
        binding.feedLoading.isVisible = showSpinner

        val stateText = when {
            // 错误分档到文案（离线/超时/服务端 user_message/鉴权……），另起一行保住「点击重试」提示
            state.showFullscreenError ->
                humanReadableErrorOf((state.refresh as LoadState.Error).throwable)
                    ?.let { "$it\n${getString(R.string.feed_error_tap_retry)}" }
                    ?: getString(R.string.list_load_failed_tap_retry)
            state.showEmptyState -> emptyStateText
            else -> null
        }
        binding.feedStateText.isVisible = stateText != null
        binding.feedStateText.text = stateText
        // 插画跟着文案走：空态=宿主给的那张(没给就不画),错误态=框架自带的路障锥,
        // 加载态(只有 spinner)隐藏。
        // imageTintList(onViewCreated 设的派生色)在 setImageResource 后保留,两张图共用同一 tint。
        val stateImage = when {
            state.showFullscreenError -> R.drawable.ic_feed_error
            state.showEmptyState -> FeedFramework.host.emptyStateImage(requireContext())
            else -> 0
        }
        if (stateImage != 0) {
            binding.feedEmptyImage.setImageResource(stateImage)
        }
        binding.feedEmptyImage.isVisible = stateImage != 0

        // 动作按钮：空态走页面自己的 action；网络类错误（断网/超时/SSL）在「点击重试」
        // 下方补一个「去网络测试」入口，其余错误态保持纯「点击重试」，加载态更没得点。
        val action = when {
            state.showEmptyState -> emptyStateAction
            state.showFullscreenError &&
                FeedFramework.host.shouldSuggestNetworkTest(
                    requireContext(),
                    (state.refresh as LoadState.Error).throwable,
                ) ->
                getString(R.string.feed_state_go_network_test) to {
                    FeedFramework.host.openNetworkTest(requireContext())
                }
            else -> null
        }
        binding.feedStateAction.isVisible = action != null
        if (action != null) {
            binding.feedStateAction.text = action.first
            binding.feedStateAction.setOnClickListener { action.second() }
        } else {
            binding.feedStateAction.setOnClickListener(null)
        }

        binding.feedStateContainer.isVisible = showSpinner || stateText != null

        // 有内容兜底时的刷新失败只提示一次，不打断浏览；已消费标记在 VM（旋转重建不重复提示）
        val refreshError = state.refresh as? LoadState.Error
        if (refreshError != null && state.items.isNotEmpty() &&
            feedViewModel.shouldNotifyRefreshError(refreshError)
        ) {
            onRefreshFailedWithContent(refreshError.throwable)
        }
    }

    private companion object {
        /** 空态 / 错误态插画的 tint 透明度（0.6f 的 8bit 值）：像插画而不是实心色块。 */
        private const val EMPTY_IMAGE_ALPHA = 153
    }
}
