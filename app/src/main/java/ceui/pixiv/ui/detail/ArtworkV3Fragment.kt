package ceui.pixiv.ui.detail

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.adapters.IllustAdapter
import ceui.lisa.adapters.ViewHolder
import ceui.lisa.databinding.FragmentArtworkV3Binding
import ceui.lisa.databinding.RecyIllustDetailBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.utils.Settings
import ceui.pixiv.actions.FollowVisibility
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.ObjectType
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.download.IllustCaptionExporter
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.services.requireNetworkStateManager
import ceui.pixiv.snapshot.AutoSnapshotEngine
import ceui.pixiv.snapshot.SnapshotArtworkFeedSource
import ceui.pixiv.snapshot.SnapshotManagerFragment
import ceui.pixiv.snapshot.SnapshotRepository
import ceui.pixiv.snapshot.SnapshotRuntimeCache
import ceui.pixiv.snapshot.localizeIllust
import ceui.pixiv.ui.comments.CommentComposerView
import ceui.pixiv.ui.comments.CommentTarget
import ceui.pixiv.ui.comments.CommentsComposerViewModel
import ceui.pixiv.ui.comments.SentComment
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.scrollToPositionWithOffset
import ceui.pixiv.ui.common.JustifiedLayoutManager
import ceui.pixiv.ui.common.JustifiedItemDecoration
import ceui.pixiv.ui.common.TabletLayout
import ceui.pixiv.ui.common.staggerIllustRenderer
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.task.PageLoadRetryController
import ceui.pixiv.ui.task.renderImageLoadStatusBanner
import ceui.pixiv.ui.upscale.IllustAiHelper
import ceui.pixiv.utils.isHostStillResumed
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.witstudio.theme.V3Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 插画详情页(feeds 框架版)。整页 = 一张异构瀑布流:顶部大图页 + header 区块(全 fullSpan)+ 相关作品瀑布流。列表 / 分页 / 空错态 / DiffUtil
 * 归框架;chrome(toolbar / 悬浮下载收藏胶囊 / 折叠胶囊 / 内联评论输入栏)浮在列表之上,由本 Fragment 直接管理。
 *
 * 顶部大图:每页是一个 [ArtworkPageItem](外层瀑布流回收),bind/recycle **委托**给本页持有的 那一个
 * [IllustAdapter]/[CollapsibleIllustAdapter] 实例(见 [ensurePageAdapter] /
 * [ArtworkV3Fragment.artworkPageRenderer]),尺寸 / 折叠 / 取图规则与 legacy 逐字一致。
 *
 * 数据源见 [ArtworkV3FeedSource];下载 FAB / 收藏态归 [ArtworkV3ViewModel]。无下拉刷新 ([refreshEnabled] = false)。
 */
class ArtworkV3Fragment : IllustFeedFragment(R.layout.fragment_artwork_v3) {
    /** 自动快照计时凭证。跨视图重建存活（与 Fragment 实例同寿），快照模式不用。 */
    private val autoSnapshot = ArtworkAutoSnapshotTracker()

    internal val snapshotId: String?
        get() = arguments?.getString(SnapshotManagerFragment.ARG_SNAPSHOT_ID)

    internal val snapshotIsAuto: Boolean
        get() = arguments?.getBoolean(SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, false) ?: false

    internal val isSnapshotMode: Boolean
        get() = snapshotId != null

    private val illustId: Long by
        lazy(LazyThreadSafetyMode.NONE) {
            // 兼容读：进程重建时恢复的 arguments 可能是旧版本按 Int 写入的
            if (isSnapshotMode) 0L else Params.getLongCompat(requireArguments(), "illust_id")
        }

    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获:只把 id/快照 id/是否自动 读进局部值交给长命 VM 持有的数据源,不钉 Fragment。
        val snapshot = arguments?.getString(SnapshotManagerFragment.ARG_SNAPSHOT_ID)
        val snapshotAuto =
            arguments?.getBoolean(SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, false) ?: false
        if (snapshot != null) {
            SnapshotArtworkFeedSource(snapshot, snapshotAuto)
        } else {
            // 平板排版（layout-sw600dp + 设置开着）的作品图在舞台里，列表只出信息区块；折叠屏
            // 开合跨过 600dp 时这个 VM 活下来，差异由 reconcilePageItems 在视图重建时对齐
            ArtworkV3FeedSource(
                Params.getLongCompat(requireArguments(), "illust_id"),
                includePages = !TabletLayout.isEnabled(resources.configuration),
            )
        }
    }

    private val artworkViewModel by
        viewModels<ArtworkV3ViewModel> {
            viewModelFactory { initializer { ArtworkV3ViewModel(illustId) } }
        }

    /** 底部内联评论输入栏的 composer VM(独立于列表)。 */
    private val composer by
        viewModels<CommentsComposerViewModel> {
            viewModelFactory {
                initializer {
                    CommentsComposerViewModel(CommentTarget(illustId, ObjectType.ILLUST))
                }
            }
        }

    internal val palette: V3Palette by
        lazy(LazyThreadSafetyMode.NONE) { V3Palette.from(requireContext()) }

    // 顶部大图页共享的那一个 adapter(所有页 bind 都委托给它)。isGif 时不建(走 ugoira renderer)。
    private var pageAdapter: IllustAdapter? = null
    private lateinit var retryController: PageLoadRetryController
    internal var aiHelper: IllustAiHelper? = null

    // chrome
    private var _chromeBind: FragmentArtworkV3Binding? = null
    private val chromeBind
        get() = checkNotNull(_chromeBind) { "view 尚未创建或已销毁" }

    // 悬浮下载/收藏胶囊的共享逻辑(与二级大图页共用),随视图创建/销毁
    private var _fabBarController: V3FabBarController? = null
    private val fabBarController
        get() = checkNotNull(_fabBarController) { "view 尚未创建或已销毁" }

    // 以下三块随视图创建 / 销毁，见各自类注释
    private var composerHost: ArtworkCommentComposerHost? = null
    private var muteMask: ArtworkMuteMask? = null
    private var actions: ArtworkV3Actions? = null
    private var fabShown = true

    /** 整页屏蔽遮罩是否正盖着。盖着时底部胶囊一律收起，见 [setMuteMaskActive]。 */
    private var muteMaskActive = false

    // ── 「首帧必然不可见」的 chrome 的懒 inflate 状态 ──────────────────────────
    //
    // 详情页 shell 有 ≈57 个 View，其中这几块（AI 覆盖层 11 个 / 屏蔽遮罩 8 个 / 状态横幅 3 个）
    // 首帧一定用不上，却在每个作品、每一页都跟着建一遍 —— ViewPager 的 offscreenPageLimit=1
    // 意味着一次点击至少建两页。这台设备单 View 构造成本被厂商实现抬得很高（见
    // framediag 日志里每个 View 一次 resolveWindowBlurOutline、每次属性读取一次 Integer 装箱），
    // 所以推迟到「真的要用」的那一刻，与 CommentComposerView 同一套打法。屏蔽遮罩那块归
    // [ArtworkMuteMask] 自己管。
    private var pageStatusRowReady = false

    private val pageStatusRowView: View
        get() = ViewCompat.requireViewById(chromeBind.root, R.id.page_status_row)
    private val pageStatusTextView: TextView
        get() = ViewCompat.requireViewById(chromeBind.root, R.id.page_status_text)

    /** 宽窗口排版的作品舞台（#1087）；手机排版与快照模式为 null，作品图照旧是列表条目。 */
    private var tabletStage: ArtworkTabletStage? = null

    private var sectionLoader: SectionLoader<ArtworkSection>? = null
    private var artistObservedUserId: Long = 0L

    /** 详情面板展开态归 Fragment(而非 cell tag):滚走再滚回不会被重绑重置(对齐 legacy VH 字段)。初始值随设置(#1044)。 */
    internal var detailPanelExpanded = !Shaft.sSettings.isDetailPanelCollapsedByDefault

    /** 超长简介展开态(#965):同上归 Fragment,默认折叠。 */
    internal var descExpanded = false

    /** 标题/简介翻译中状态，用于控制元信息栏/简介区翻译按钮与转圈的显隐与禁用。 */
    internal var isTranslating = false

    /**
     * 不能 findViewById 直接改:滑出去的信息区/简介区躺在 view cache 里(见 setItemViewCacheSize),
     * 不在 RecyclerView 子 View 中、滑回来也不重绑,会一直转圈且按钮禁用。notifyItemChanged
     * 连缓存里的 holder 一起标脏,由 renderer 按 [isTranslating] 重绑;在屏的只按
     * [PAYLOAD_TRANSLATING_STATE] 刷按钮,不动标题/简介文本。
     */
    internal fun setTranslatingState(translating: Boolean) {
        if (isTranslating == translating) return
        isTranslating = translating
        if (_chromeBind == null) return
        val fa = feedAdapter ?: return
        fa.currentList.forEachIndexed { pos, item ->
            if (item is ArtworkHeroItem || item is ArtworkDescItem) {
                fa.notifyItemChanged(pos, PAYLOAD_TRANSLATING_STATE)
            }
        }
    }

    /**
     * 一键跳评论(#970)落点后是否还在钉基线:首跳时评论/作者作品/相关往往都还是加载态, 视口下方内容不够,SGLM 修 end gap 会把评论块顶离 toolbar
     * 基线;且各区块落地时序不定, 补对一次不够——数据每变一次就补对一次(见 [alignCommentsIfPending]),直到下方内容 足够(end gap
     * 不会再动它)才算收敛。用户一拖动即作废,不抢用户的滚动。
     */
    private var commentsJumpRealign = false

    /** 页码读数当前指着哪一页(0 基);浮标不在场时为 -1。长按预览拿它当高亮/初始滚动位。 */
    private var pageProgressIndex = -1

    /** 预览里点了一页、但那一页还被折叠着:展开后的条目一落地就补跳过去。 */
    private var pendingPageJump: Int? = null

    /**
     * 本次视图生命周期里，视口是否被 [ViewerPageLink]（二级大图翻页）挪动过。
     *
     * 用来区分「用户翻回进场页」该不该跟着回：没挪过就说明他根本没翻走，别去动他自己滚出来的位置。
     */
    private var viewerViewportSynced = false

    /** 大图最后要求本页停在第几页（-1 = 没要求过）。退出归位要靠它定位「当前页那一格」。 */
    private var viewerSyncedPage = -1

    /**
     * [ViewerPageLink] 用的作品键。
     *
     * 快照页的 [illustId] 恒为 0L（快照只认 snapshotId），得从快照里那份 illust 取真实 id 才能和
     * 大图侧广播的对上；取不到时给 0，匹配自然落空（安全降级）。
     */
    private val viewerLinkIllustId: Long
        get() = if (isSnapshotMode) {
            snapshotId?.let { SnapshotRuntimeCache.get(it) }?.illust?.id ?: 0L
        } else {
            illustId
        }

    /** 收起后欠着的一次回顶:等「隐藏页被移除」的通知到了再发,理由见 [drainCollapseResetScroll]。 */
    private var pendingCollapseResetScroll = false

    /** 解析好的完整简介(#965):折叠态显示的是截断文本,展开/重绑时从这里取回全文。 */
    internal var descFullCaption: CharSequence? = null

    // 关闭下拉刷新(详情页 feeds 版不支持)
    override val refreshEnabled: Boolean = false

    // 宽窗口：列表铺在半透明信息栏上，自己不画底色（信息栏的底色由 ArtworkTabletStage 给）
    override val feedRootBackgroundColor: Int
        get() = if (usesTabletStage()) Color.TRANSPARENT else super.feedRootBackgroundColor

    // TabletLayout 的最小宽度判定与 layout-sw600dp 限定符是同一条线：命中时舞台 view 必然存在；
    // 平板上关掉「平板适配排版」时仍拿到 sw600dp 布局，舞台收起、信息栏铺满，等同手机排版
    private fun usesTabletStage(): Boolean =
        !isSnapshotMode && TabletLayout.isEnabled(resources.configuration)

    // ── 列表装配 ────────────────────────────────────────────────────────────

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(
            artworkPageRenderer(),
            artworkPinnedFirstPageRenderer(),
            artworkUgoiraRenderer(),
            heroRenderer(),
            seriesRenderer(),
            descRenderer(),
            statsRenderer(),
            tagsRenderer(),
            artistRenderer(),
            detailPanelRenderer(),
            commentsRenderer(),
            authorWorksRenderer(),
            relatedHeaderRenderer(),
            staggerIllustRenderer(),
        )
    }

    /** 首屏是作品大图，插画卡在相关作品区、远在首屏之外：不预建。 */
    override val firstScreenPrewarmItemClass: Class<out FeedItem>?
        get() = null

    // onCreateLayoutManager 不覆写:用父类 IllustFeedFragment 按「插画列表布局」装的那份(瀑布流 / 方格 /
    // 单列是 StaggeredManager,齐行是 JustifiedLayoutManager)。本页的跳评论 / 跳页 /
    // 收起回顶都走 scrollToPositionWithOffset 扩展,两种 LayoutManager 都认。

    override fun onListReady(listView: RecyclerView) {
        // 相关作品跟随「插画列表布局」设置(#1214)。间距对齐外面的推荐插画流(8dp),只给相关卡片:
        // 瀑布流 / 方格 / 单列走 SGLM 的 RelatedOnlySpaceDecoration;齐行的外缘由 LayoutManager 让出,
        // 大图 / 简介 / 评论这些整行区块用负间距抵掉、照旧贴边(列表 View 的 padding 仍归
        // handleSystemInsets 管,不碰)。
        val manager = listView.layoutManager
        listView.addItemDecoration(
            if (manager is JustifiedLayoutManager) {
                JustifiedItemDecoration(manager, fullBleedFullSpan = true)
            } else {
                RelatedOnlySpaceDecoration(8.ppppx)
            }
        )
        // header 区块(fullSpan)在 notifyItemChanged 时的默认变更动画会打乱 SGLM 的 fullSpan 追踪。
        listView.itemAnimator = null
        // 多留几格「刚滑出去的 holder」。RecyclerView 对缓存里的 holder 不调 onViewRecycled、
        // 也不清 Glide 请求,滑回来(含收起回顶)直接复用,连图都还在,不会先露一帧页面底色。
        // 这才是「保住已画好的位图」的安全做法:位图仍归 Glide 的引用计数管着。自己存一份裸
        // Bitmap 引用是错的——Glide 回收它时我们还在画,draw 阶段直接崩 Canvas。
        // 6 格按「收起前一般只往下翻几页」定;调大只是多占内存,语义不变。
        listView.setItemViewCacheSize(6)
        // 跳评论(#970)基线收敛:懒加载区块每次落地(change/insert)都可能触发 end-gap 修正,
        // 数据一变就再对一次。观察者随本次 install 的 adapter 一起活/一起丢,不需手动反注册。
        feedAdapter?.registerAdapterDataObserver(
            object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                    scheduleCommentsRealign()
                    // 快速展开再收起可能合并成仅覆盖层变化，没有 removal 通知。
                    drainCollapseResetScroll()
                }

                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    scheduleCommentsRealign()
                    // 展开多图产出的页条目也是从这里落地的,长按预览欠着的那一跳在此接上(#1085)。
                    drainPendingPageJump()
                }

                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                    // 收起产出的移除落地了:回顶在这里发,和它被同一次 layout 消费,两者才是一帧。
                    drainCollapseResetScroll()
                }
            }
        )
    }

    // 详情页首屏是大图 + header,不是瀑布流网格——瀑布流骨架图会误导。用居中转圈圈(对齐 legacy)。
    override fun onCreateSkeletonView(
        layoutManager: RecyclerView.LayoutManager
    ): ceui.pixiv.feeds.FeedSkeletonView? = null

    override fun onListCommitted(state: FeedUiState) {
        super.onListCommitted(state)
        // 出错后重试等重新加载，数据源仍按建 VM 时的排版出图；和当前排版不一致时对齐一次
        if (_chromeBind != null) reconcilePageItems()
        // 自动展开保留了旋屏前的全 P 列表。视口若已在简介/评论区，不会绑定任何图片，
        // 不能等 artworkPageRenderer 才接回「收起」入口；提交后即可按列表初始化展开态。
        if (_chromeBind != null && pageAdapter == null &&
            CollapsibleIllustAdapter.shouldCollapse(currentPageCount()) && listHasAllPages()
        ) {
            ensurePageAdapter()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _chromeBind = FragmentArtworkV3Binding.bind(view)
        _fabBarController = V3FabBarController(chromeBind.fabBar)
        setUpTabletStage()
        if (tabletStage == null) {
            // 页码浮标 / 长按预览都是「列表里的图片页」的配件；舞台自带页码条，不挂
            attachPageProgressPill()
            attachPagesPreview()
        }
        // 必须在快照的 early-return 之前：快照大图同样走这条联动。
        wireViewerPageLink()
        // 评论区的「留下你的评论吧」入口两种模式都会调 showComposer，host 一并在此建（不 inflate）。
        composerHost =
            ArtworkCommentComposerHost(
                fragment = this,
                chrome = chromeBind,
                listView = feedBinding.feedListView,
                palette = palette,
                composer = { composer },
                onSent = ::applySentComment,
                hideFabBar = { immediate -> hideFabBar(immediate) },
                showFabBar = ::showFabBar,
            )
        if (isSnapshotMode) {
            // 快照只读：保留收藏/关注按钮用于展示“那一刻”的状态，但点击一律无动作。
            chromeBind.fabBar.root.isVisible = true
            chromeBind.fabBar.fabDownloadContainer.isVisible = false
            chromeBind.fabBar.fabDivider.isVisible = false
            chromeBind.fabBar.fabBookmark.setOnClickListener {
                Common.showToast(getString(R.string.snapshot_unsupported_toast))
            }
            chromeBind.fabBar.fabBookmark.setOnLongClickListener {
                Common.showToast(getString(R.string.snapshot_unsupported_toast))
                true
            }
            fabBarController.applyPalette(palette)
            fabBarController.applyLayoutPreference(sideMargin = 20.ppppx)
            applySnapshotBookmarkState()
            chromeBind.composerRoot.isVisible = false
            chromeBind.navMore.isVisible = false
            chromeBind.toolbar.setNavigationOnClickListener { requireActivity().finish() }
            handleSystemInsets()
            return
        }
        sectionLoader =
            SectionLoader<ArtworkSection>(viewLifecycleOwner) { it.load(illustId, feedViewModel) }
        muteMask = ArtworkMuteMask(this, chromeBind, illustGlide, ::setMuteMaskActive)
        actions =
            ArtworkV3Actions(
                fragment = this,
                illustId = illustId,
                viewModel = artworkViewModel,
                fabBar = fabBarController,
                currentPageIndex = { tabletStage?.currentPage ?: resolveCurrentViewportPage() },
                aiHelper = { aiHelper },
                onOpenComicReader = ::openComicReader,
                onForceOriginal = ::applyForceOriginal,
            )
        aiHelper =
            IllustAiHelper(
                fragment = this,
                rootView = chromeBind.root,
                onAiStateChanged = { pageIndex ->
                    onAiPageChanged(pageIndex)
                },
                onAiProgress = { pageIndex, progress, eta, statusText ->
                    onAiPageProgress(pageIndex, progress, eta, statusText)
                },
                onAiTaskCompleted = ::onAiTaskCompleted,
            ).also {
                it.restoreUpscaleIfRunning(illustId)
            }

        // 旋转 / 视图重建:feedViewModel 的列表存活(可能是展开态),但 pageAdapter 会重建为
        // 折叠态。二者不一致会出「p0 顶着展开胶囊、p1/p2 却已显示」的矛盾 UI。对齐 legacy(旋转即
        // 折叠):在任何页绑定前(此刻 uiState 尚未 render)把多出的页收回,保持与新 adapter 一致。
        ObjectPool.get<Illust>(illustId).value?.let { illust ->
            // 开了「多图自动展开」(#1090)就不再收回:列表保持全 P,新 adapter 也随之取展开态。
            if (CollapsibleIllustAdapter.shouldCollapse(illust.page_count) && !autoExpandEnabled()) {
                feedViewModel.removeItems { it is ArtworkPageItem && it.pageIndex > 0 }
            }
        }

        retryController =
            PageLoadRetryController(
                lifecycleOwner = viewLifecycleOwner,
                networkStateManager = requireNetworkStateManager(),
                totalPages = { ObjectPool.get<Illust>(illustId).value?.page_count ?: 0 },
                onSummaryChanged = { loaded, total, failed ->
                    // 状态横幅首帧必然不可见（只在有页加载失败时才亮），第一次真有失败才建它。
                    // 失败是罕见路径，所以绝大多数页从头到尾都不会付这 3 个 View 的构造成本。
                    if (failed > 0) {
                        ensurePageStatusRow()
                        renderImageLoadStatusBanner(
                            pageStatusRowView,
                            pageStatusTextView,
                            loaded,
                            total,
                            failed,
                        )
                    } else if (pageStatusRowReady) {
                        pageStatusRowView.isVisible = false
                    }
                },
                onRetryAt = { idx ->
                    val fa = feedAdapter ?: return@PageLoadRetryController
                    val pos =
                        fa.currentList.indexOfFirst {
                            it is ArtworkPageItem && it.pageIndex == idx
                        }
                    if (pos >= 0) fa.notifyItemChanged(pos)
                },
            )

        setupFabBar()
        setupNavBar()
        handleSystemInsets()
        // composer 不在这里装配:它的视图树(含 ViewPager2)首帧既不可见也用不上,
        // 却要为它付一次完整 inflate。推迟到首次唤出,见 ArtworkCommentComposerHost。

        // 隐藏 / 显示悬浮胶囊(滚动);用户主动拖动时作废还欠着的跳评论基线校正
        feedBinding.feedListView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 8) hideFabBar() else if (dy < -8) showFabBar()
                    refreshPageProgressPill()
                    syncAutoSnapshotViewport()
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) commentsJumpRealign = false
                }
            }
        )

        // 关注态:观察作者 User,变更后重算 Artist 条目(关注切换只这条重绑)。
        // 顺带接住 caption 后台补拉的落地(见 ArtworkV3ViewModel.ensureTrustedCaption)。
        ObjectPool.get<Illust>(illustId).observe(viewLifecycleOwner) { illust ->
            illust ?: return@observe
            tabletStage?.bind(illust)
            syncDescSection(illust.caption, illust.title)
            muteMask?.attach(illust)
            val authorId = illust.user?.id ?: return@observe
            attachArtistFollowObserver(authorId)
        }

        // issue #1023: 标签编辑 sheet 是 childFragmentManager 拉起来的(见 tagsRenderer),
        // 变更经 fragment result 回来而不是 lambda —— sheet 跨横屏会重建,回调必然失效。
        childFragmentManager.setFragmentResultListener(
            TagEditSheet.REQUEST_TAGS_CHANGED,
            viewLifecycleOwner,
        ) { _, _ ->
            refreshTagsSection()
        }

        // 若当前处于 ViewPager 离屏预加载状态（非 RESUMED），在首帧绘制完成后通过 post 稍后加载相邻页数据，
        // 既避开与当前页同帧竞争主线程造成的严重卡顿（128 帧丢帧），又保证用户滑动时邻页已在内存准备就绪。
        if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            view.post {
                if (isAdded && !isDetached) {
                    feedViewModel.ensureLoaded()
                }
            }
        }
    }

    // ── 「首帧必然不可见」的 chrome 的懒 inflate ──────────────────────────────
    //
    // ensure* 各自只被「真的要用」的那条路径调用，幂等。理由见上面字段处的注释。

    /** 图片加载状态横幅：只有真有页加载失败才亮。触发点 = onSummaryChanged 第一次报 failed>0。 */
    private fun ensurePageStatusRow() {
        if (pageStatusRowReady) return
        pageStatusRowReady = true
        chromeBind.pageStatusRowStub.inflate()
        // 「全部重试」的接线跟着这块一起延迟 —— 它原来在 onViewCreated 里无条件接。
        ViewCompat.requireViewById<View>(chromeBind.root, R.id.page_status_retry)
            .setOnClickListener { retryController.retryAllFailed() }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoSnapshot.restore(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        autoSnapshot.save(outState)
    }

    override fun onResume() {
        super.onResume()
        // 大图会话已结束（退出动画播完才 finish、这里才 resume）：联动状态归零。否则
        // viewerSyncedPage 会一直 ≥ 0，让 publishViewportRect 在之后每一次排版空跑。
        viewerViewportSynced = false
        viewerSyncedPage = -1
        if (isSnapshotMode) {
            // FeedSource 加载完成后用快照里的真实收藏态刷新只读心形按钮（兼容旧快照回落 illust.json）。
            // 走同一个 helper 而不是在这里再内联一遍:那份内联只查 SnapshotRuntimeCache,
            // 缓存没命中(4 条 LRU 被挤掉、进程重建)就什么都不做,心形停在默认灰;
            // helper 有回落读 manifest 的 IO 分支。
            applySnapshotBookmarkState()
            return
        }
        autoSnapshot.onResume(illustId, ObjectPool.get<Illust>(illustId).value)
        // 横滑回来：表被挂起过，按当前视口决定接着走还是继续挂着 —— 滑走前停在相关作品那一段的页，
        // 滑回来时不一定有排版回调，不能无条件续算（从没停过、仍在作品区时这里是空操作）。
        syncAutoSnapshotViewport()
        artworkViewModel.onPageVisible()
        artworkViewModel.refreshDownloadFab()
        refreshCachedOriginalPages()
        aiHelper?.restoreAiTasksIfRunning(illustId)
        refreshAllAiPages()
    }

    /**
     * 从二级大图页返回后，把进程内已缓存 ORIGINAL 的顶部大图页回填成原图。 遍历和快照/原图模式防御都在
     * [IllustAdapter.showCachedOriginalOverlays] 内。
     */
    private fun refreshCachedOriginalPages() {
        if (isSnapshotMode) return
        pageAdapter?.showCachedOriginalOverlays()
    }

    override fun onPause() {
        if (!isSnapshotMode) {
            autoSnapshot.onPause(hostStillResumed = isHostStillResumed())
            artworkViewModel.pauseDownloadFab()
        }
        super.onPause()
    }

    override fun onStop() {
        autoSnapshot.onStop(changingConfigurations = activity?.isChangingConfigurations == true)
        super.onStop()
    }

    override fun onDestroyView() {
        tabletStage?.release()
        tabletStage = null
        autoSnapshot.onDestroyView()
        composerHost = null
        muteMask = null
        actions = null
        // 懒 inflate 的标志随视图销毁归零：视图重建后拿到的是新的 ViewStub，
        // 不归零的话 ensure*() 会误判为「已经建过」而跳过，那几块就再也不出现了。
        pageStatusRowReady = false
        fabShown = true
        muteMaskActive = false
        // 跳评论的基线钉扎(#970)随视图作废:留着的话,视图重建(回退栈重显/旋转)后首次
        // render 的数据落地就会把新列表拽去评论区。
        commentsJumpRealign = false
        pendingPageJump = null
        pendingCollapseResetScroll = false
        pageProgressIndex = -1
        viewerViewportSynced = false
        viewerSyncedPage = -1
        pageAdapter?.release()
        pageAdapter = null
        // 本视图生命周期内的一次性 guard 随视图销毁归零。否则同一 Fragment 实例视图重建(回退栈
        // 重显等)后,旧 viewLifecycleOwner 上的观察已随视图销毁,而 artistObservedUserId 还钉着
        // 旧值 → 关注更新不再触发。区块懒加载的去重集随 sectionLoader 一起丢弃、新视图重建。
        artistObservedUserId = 0L
        sectionLoader = null
        // helper 持有本次 rootView；Fragment 留在返回栈时必须随 View 生命周期断开引用。
        aiHelper = null
        _fabBarController = null
        // 收起胶囊的淡出动画挂在 Choreographer 上,view 从窗口摘下来它照样 tick 到底,
        // 结束时那个 withEndAction 会去碰 chromeBind —— 此刻已是 null,checkNotNull 直接抛。
        // (220ms 的窗口:3P+ 作品点「收起」的当下旋屏 / 返回就能撞上。)
        _chromeBind?.collapsePill?.animate()?.cancel()
        _chromeBind = null
        super.onDestroyView()
    }

    // ── 顶部大图 adapter(委托目标)────────────────────────────────────────────

    /** 首次绑定顶部页时懒建那一个共享 adapter(尺寸 / 折叠 / 取图逻辑全在它里面)。 */
    internal fun ensurePageAdapter(): IllustAdapter? {
        pageAdapter?.let {
            return it
        }
        if (isSnapshotMode) return ensureSnapshotPageAdapter()
        if (view == null || !::retryController.isInitialized) return null
        val illust = ObjectPool.get<Illust>(illustId).value ?: return null
        if (illust.isGif()) return null // ugoira 走自己的 renderer
        val adapter: IllustAdapter =
            createCollapsibleAdapter(
                illust,
                artworkViewModel.forceOriginalPreview,
                onComicReaderClick = { openComicReader() },
            )
        // p0 的 holder 常驻:见 IllustAdapter.setKeepPinnedPages 与 ArtworkPinnedFirstPageItem。
        adapter.setKeepPinnedPages(true)
        adapter.setPageStatusListener { position, status ->
            retryController.reportStatus(position, status)
        }
        adapter.setLocalPagesChangedListener {
            // IllustAdapter 在 feeds 版只是 bind delegate，并未直接挂到 RecyclerView；它自己的
            // notifyDataSetChanged 无效。下载记录扫描命中后 bump tick，让外层 FeedAdapter 重绑。
            if (_chromeBind != null) {
                feedViewModel.updateItems<ArtworkPageItem> {
                    it.withRebindTick(it.rebindTick + 1)
                }
            }
        }
        retryController.refresh()
        // 每页真实宽高由 [ArtworkV3ViewModel.pageDimensions] 承载(VM 多 P 时拉一次网页 ajax)。
        // 这里把「已到」的值补给新建的 adapter;「后到」的值由 setup 处的观察者补上。
        artworkViewModel.pageDimensions.value?.let { adapter.seedPageDimensions(it) }
        pageAdapter = adapter
        return adapter
    }

    /**
     * 只读心形按钮：显示快照那一刻的收藏态。缓存已预热(管理页进入前会 preload)时同步取， 否则退到 IO 上读 manifest —— 不在 onViewCreated
     * 里直接碰磁盘。
     */
    private fun applySnapshotBookmarkState() {
        val id = snapshotId ?: return
        SnapshotRuntimeCache.get(id)?.let {
            fabBarController.setBookmarked(it.manifest.isBookmarked || it.illust.isBookmarked)
            return
        }
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val bookmarked =
                withContext(Dispatchers.IO) {
                    runCatching { SnapshotRepository.readManifest(appContext, id)?.isBookmarked }
                        .getOrNull()
                } ?: false
            fabBarController.setBookmarked(bookmarked)
        }
    }

    /** 快照模式专用：从快照库读取 bean，并把每页图片直接指向快照本地文件。 */
    private fun ensureSnapshotPageAdapter(): IllustAdapter? {
        val snapshotId = snapshotId ?: return null
        val data = SnapshotRuntimeCache.get(snapshotId) ?: return null
        val illust = data.localizeIllust()
        if (illust.isGif()) return null
        val adapter: IllustAdapter =
            createCollapsibleAdapter(illust, forceOriginal = false, onComicReaderClick = null)
        // 必须先打快照标记再喂本地页:adapter 构造时已经发出一趟「已下载文件」后台扫描,
        // 标记会让它回主线程合并时整个作废,避免同 ID 的下载文件顶掉快照里的那一份。
        adapter.setSnapshotId(snapshotId)
        adapter.setSnapshotIsAuto(snapshotIsAuto)
        val pageCount = illust.page_count.coerceAtLeast(1)
        for (i in 0 until pageCount) {
            data.pageFile(i)?.let { file -> adapter.putLocalPageUri(i, Uri.fromFile(file)) }
        }
        adapter.setKeepPinnedPages(true)
        adapter.setPageStatusListener { _, _ -> }
        adapter.setLocalPagesChangedListener(null)
        pageAdapter = adapter
        return adapter
    }

    /** 顶部大图单页的最大展示高度,与 legacy 一致取屏高 70%。 */
    private fun pageMaxHeight(): Int = (resources.displayMetrics.heightPixels * 0.7f).toInt()

    /** 3P+ 的折叠 adapter,普通与快照两条路共用。顺带接好右上角「收起」胶囊。 */
    private fun createCollapsibleAdapter(
        illust: Illust,
        forceOriginal: Boolean,
        onComicReaderClick: (() -> Unit)?,
    ): CollapsibleIllustAdapter {
        val collapsible =
            CollapsibleIllustAdapter(
                requireActivity(),
                this,
                illust,
                pageMaxHeight(),
                forceOriginal,
                initiallyExpanded = listHasAllPages(),
                onComicReaderClick = onComicReaderClick,
                onExpandedChanged = { expanded -> onPagesExpandedChanged(expanded) },
                onExpandPillLongClick = { openPagesPreview() },
            ).apply {
                onUpscalePillClick = { pos, state ->
                    aiHelper?.openUpscaleComparison(state.upscaleResultPath, state.upscaleOriginalPath)
                }
                onRembgPillClick = { pos, state ->
                    aiHelper?.openRembgHighlight(state.rembgResultPath, state.rembgOriginalPath)
                }
            }
        // 悬浮「收起」胶囊点击 → 折叠(collapse() 触发 onExpandedChanged(false) → 收回页 + 回顶 + 藏胶囊)
        chromeBind.collapsePill.setOnClickListener { collapsible.collapse() }
        // 进页即展开态不会走 onExpandedChanged(true)，胶囊得由这里点亮：
        // 长作品更需要一个「收起」出口，否则只能一路滑到底。
        if (collapsible.isExpanded) showCollapsePill(animate = false)
        return collapsible
    }

    /**
     * “加载原图”：重建共享的顶层大图 adapter（isForceOriginal=true）， 并 bump 所有页面条目的 rebindTick，让外层 FeedAdapter
     * 原地重绑。 多 P 折叠作品保留展开态，避免点一下菜单就折回第一页。
     */
    private fun applyForceOriginal() {
        // 全局已经是原图模式时，当前 adapter 本来就按 original 加载。不要为了一个等价状态
        // 释放 / 重建 adapter、重扫本地下载并重绑全部已展开页面，避免无意义的闪动和 IO。
        if (Shaft.sSettings.isShowOriginalPreviewImage || artworkViewModel.forceOriginalPreview)
            return
        // 先置位再动 adapter：若首帧大图还没懒建（pageAdapter == null），
        // 后续 ensurePageAdapter() 也会带着这个开关创建，点击不丢。
        artworkViewModel.forceOriginalPreview = true
        tabletStage?.reloadPages()
        val old = pageAdapter ?: return
        val wasExpanded = (old as? CollapsibleIllustAdapter)?.isExpanded == true
        old.release()
        pageAdapter = null
        val newAdapter = ensurePageAdapter() ?: return
        if (wasExpanded) {
            (newAdapter as? CollapsibleIllustAdapter)?.expand()
        }
        feedViewModel.updateItems<ArtworkPageItem> { it.withRebindTick(it.rebindTick + 1) }
    }

    private fun openComicReader() {
        startActivity(
            Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.COMIC_READER.key)
                putExtra(Params.ILLUST_ID, illustId)
            }
        )
    }

    /** 折叠 adapter 的展开态回调:驱动 feed 列表增删剩余页 + 浮动「收起」胶囊。 */
    private fun onPagesExpandedChanged(expanded: Boolean) {
        // 展开/收起本身就带滚动意图(收起要回顶),它触发的条目增删不能再被跳评论的
        // 基线钉扎(#970)拽回评论区——收起胶囊点击不经过 DRAGGING,得在这里主动作废。
        commentsJumpRealign = false
        val pill = chromeBind.collapsePill
        pill.animate().cancel()
        if (expanded) {
            pendingCollapseResetScroll = false
            showCollapsePill(animate = true)
            val pageCount = currentPageCount()
            if (pageCount <= 0) return
            feedViewModel.mutateItems { items ->
                val existing =
                    items.filterIsInstance<ArtworkPageItem>().mapTo(HashSet()) { it.pageIndex }
                val toAdd =
                    (1 until pageCount)
                        .filter { it !in existing }
                        .map { artworkPageItem(illustId, it) }
                if (toAdd.isEmpty()) return@mutateItems items
                val insertAt = items.indexOfLast { it is ArtworkPageItem } + 1
                items.subList(0, insertAt) + toAdd + items.subList(insertAt, items.size)
            }
        } else {
            pill
                .animate()
                .alpha(0f)
                .setDuration(220)
                .withEndAction {
                    // onDestroyView 已 cancel 过一遍(cancel 不派发 end action),这里是第二道:
                    // 动画结束回调不保证跑在 view 还活着的时候,syncTopEndPill 会 checkNotNull(chromeBind)。
                    if (_chromeBind == null) return@withEndAction
                    pill.visibility = View.GONE
                    pill.alpha = 1f
                    syncTopEndPill()
                }
                .start()
            pendingPageJump = null
            pendingCollapseResetScroll = isViewingPages()
            // 一次编辑同时:删掉隐藏页 + bump 首页 overlayTick(强制 DiffUtil 原地重绑 p0,
            // 让「展开剩余 X 张」覆盖层重现)。不用 notifyItemChanged/post,避免与在飞的 diff 抢。
            feedViewModel.mutateItems { items ->
                items.mapNotNull { item ->
                    when {
                        item is ArtworkPageItem && item.pageIndex > 0 -> null
                        item is ArtworkPageItem && item.pageIndex == 0 ->
                            item.withOverlayTick(item.overlayTick + 1)
                        else -> item
                    }
                }
            }
            // 收起后回不回顶，取决于用户此刻在不在页块里。已经滑到简介 / 评论 / 相关作品时
            // 把画面弹回第一张是反直觉的——他要的是把图折起来，不是回到顶；而且这一弹会连带
            // 把刚滑出去的信息区条目重新绑一遍（作者头像会重发一次请求）。RecyclerView 把视口
            // 锚在当前可见条目上，删除它上方的内容不会让画面跳，所以「不动」就是对的。
            // 回顶本身欠到「移除落地」时再发,见 [drainCollapseResetScroll]:此刻列表还是旧的 N 条,
            // 当场滚会先滚在一份还没缩短的内容上,顶部那条旧页会先亮一帧。
        }
    }

    /**
     * 池 / 快照两条来源统一取当前作品的 bean;取不到给 null。
     *
     * 快照模式下 [illustId] 恒为 0L(快照只认 snapshotId),所以任何直接 `ObjectPool.get<Illust>(illustId)`
     * 的地方在快照里都是静默拿到 null —— 凡是两种模式 都要用的读取都得走这里。
     */
    private fun currentIllust(): Illust? {
        return if (isSnapshotMode) {
            snapshotId?.let { SnapshotRuntimeCache.get(it) }?.illust
        } else {
            ObjectPool.get<Illust>(illustId).value
        }
    }

    /** 池 / 快照两条来源统一取当前作品的总页数;取不到给 0。 */
    private fun currentPageCount(): Int = currentIllust()?.page_count ?: 0

    /** 设置「多图自动展开」(#1090)：开则数据源一次产出全 P，旋屏也不再收回。 */
    private fun autoExpandEnabled(): Boolean = Shaft.sSettings.isArtworkV3AutoExpandMultiPage

    /**
     * 列表里是否已经摆着全部页 —— adapter 的「进页即展开」以**列表实际内容**为准，不直接读设置。
     * 设置是进页那一刻的快照：进页后才打开开关再旋屏，列表仍只有 p0；若 adapter 照样按设置展开，
     * 就会既不出「展开剩余 X 张」覆盖层、又没得展开，卡死在第一页。
     * 用 VM 的最新条目：收起后的 diff 可能还没提交，此时「加载原图」重建 adapter 不能读旧的 currentList。
     */
    private fun listHasAllPages(): Boolean {
        val items = feedViewModel.uiState.value.items
        // 折叠态只留 p0（DEFAULT_COLLAPSED），比它多就是已经摆成展开形状了。
        return items.count { it is ArtworkPageItem } > CollapsibleIllustAdapter.DEFAULT_COLLAPSED
    }

    /**
     * 让右上角「收起」胶囊出现。两处调用：用户点展开([onPagesExpandedChanged])走 220ms 淡入；
     * 设置「多图自动展开」决定的进页初始态不走动效——那不是用户动作，不该有入场表演。
     * alpha 先落到终值再显形，免得出现「先满亮、再淡入」的一帧。
     */
    private fun showCollapsePill(animate: Boolean) {
        val pill = chromeBind.collapsePill
        pill.animate().cancel()
        if (animate) pill.alpha = 0f else pill.alpha = 1f
        pill.visibility = View.VISIBLE
        syncTopEndPill()
        if (animate) pill.animate().alpha(1f).setDuration(220).start()
    }

    /**
     * 视口里还有页条目 —— 用户正在看图。收起只在此时才回顶，见 [onPagesExpandedChanged]。
     * 拿不到列表 / 布局管理器时按 true 处理，退回「一律回顶」的旧行为。
     */
    private fun isViewingPages(): Boolean {
        if (_chromeBind == null) return true
        val items = feedAdapter?.currentList ?: return true
        val listView = feedBinding.feedListView
        val lm = listView.layoutManager ?: return true
        for (i in 0 until listView.childCount) {
            if (items.getOrNull(lm.getPosition(listView.getChildAt(i))) is ArtworkPageItem) return true
        }
        return false
    }

    /**
     * 视口里还有**本作品**的条目 —— 用户还在看这个作品，表该走；只剩「作者其他作品」「相关作品」
     * 那些别人的作品了，表该挂起（见 [syncAutoSnapshotViewport]）。
     *
     * 用白名单而不是「还有没有 fullSpan 条目」：[ArtworkAuthorWorksItem] 与
     * [ArtworkRelatedHeaderItem] 也是 fullSpan，按 fullSpan 判会把它们算回本作品，而按边界它们
     * 已经出了本作品。
     *
     * 动图（[ArtworkUgoiraItem]）刻意不在白名单：自动快照本来就不含动图，凭证在
     * [AutoSnapshotEngine.onArtworkPageVisible] 里就被判成 null，这里根本不会被问到。
     *
     * 拿不到列表 / 还没排版时按「在看」处理，退回改动前的行为 —— 否则一进页就会先挂起。
     */
    private fun isArtworkInViewport(): Boolean {
        val items = feedAdapter?.currentList ?: return true
        val listView = feedBinding.feedListView
        val lm = listView.layoutManager ?: return true
        if (listView.childCount == 0) return true
        for (i in 0 until listView.childCount) {
            when (items.getOrNull(lm.getPosition(listView.getChildAt(i)))) {
                is ArtworkPageItem,
                is ArtworkHeroItem,
                is ArtworkSeriesItem,
                is ArtworkArtistItem,
                is ArtworkDescItem,
                is ArtworkTagsItem,
                is ArtworkStatsItem,
                is ArtworkDetailPanelItem,
                is ArtworkCommentsItem -> return true

                else -> Unit
            }
        }
        return false
    }

    /**
     * 按视口把自动快照的表挂起 / 续算。挂在滚动与排版回调上（与 [refreshPageProgressPill] 同源），
     * 所以「滚到相关作品」「滚回作品区」「从上层页返回时列表本来就停在相关作品」三种情况都覆盖。
     *
     * 平板不看视口、一律按「在看」：作品钉在 [ArtworkTabletStage] 里始终可见（见其类注释），信息栏滚到哪都不代表
     * 作品离开视线；而且 [usesTabletStage] 命中时列表里压根没有页条目（见
     * [ArtworkV3FeedSource] 的 includePages），照手机判据会一进页就挂起、整段会话都不恢复。
     */
    private fun syncAutoSnapshotViewport() {
        autoSnapshot.syncViewport(isResumed) { usesTabletStage() || isArtworkInViewport() }
    }

    /**
     * 右上角常驻页码浮标(#1058):不进阅读器、直接在详情页往下滑看多图时,标出「当前页 / 总页」。
     *
     * 「当前页」取**正被浮标盖着的那一页**,而不是视口正中那一页——浮标就悬在顶栏下方,拿它自己 那条线去问「我盖着谁」最直观;竖幅长图也不会因为中线正好落在页缝里而跳数。具体是:可见的
     * 页条目里,顶边已经越过锚线的最后一页;都还没越过(刚进页面)就取最靠前那页。
     *
     * 一页都不在屏幕上了(滑到简介 / 评论 / 相关作品)就收起——那时已经不是在「看图」。 折叠态(3P+ 未展开)只有 p0 在列表里,读数照样是「1 / N」,和 web 端一致。
     */
    private fun refreshPageProgressPill() {
        // 平板舞台自带页码读数；列表里没有图片条目，不必每帧滚动都遍历子 view
        if (_chromeBind == null || tabletStage != null) return
        val pill = chromeBind.pageProgressPill
        val total = currentPageCount()
        // 单图 / 动图没有「第几页」可言
        if (total <= 1) {
            pageProgressIndex = -1
            applyPageProgressPill(pill, null)
            return
        }
        val listView = feedBinding.feedListView
        val items = feedAdapter?.currentList
        val layoutManager = listView.layoutManager
        if (items == null || layoutManager == null) {
            pageProgressIndex = -1
            applyPageProgressPill(pill, null)
            return
        }
        val anchorY = chromeBind.topOverlayColumn.bottom
        var current = -1
        var firstVisible = -1
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i)
            val item =
                items.getOrNull(layoutManager.getPosition(child)) as? ArtworkPageItem ?: continue
            if (firstVisible < 0 || item.pageIndex < firstVisible) firstVisible = item.pageIndex
            if (child.top <= anchorY && item.pageIndex > current) current = item.pageIndex
        }
        if (current < 0) current = firstVisible
        if (current < 0) {
            pageProgressIndex = -1
            applyPageProgressPill(pill, null)
            return
        }
        pageProgressIndex = current
        applyPageProgressPill(pill, getString(R.string.artwork_page_indicator, current + 1, total))
    }

    /**
     * 把读数段（「当前页 / 总页」）的**文字与显隐一起**落到右上角胶囊上，且**必须落在布局之外**。
     *
     * 文字要推迟的理由（#1058 那版踩过）：写 text 会 requestLayout，若这次写入落在布局过程中，
     * 框架会把它并进第二趟布局、或直接吞掉（logcat 实证："requestLayout() improperly called by ...
     * app:id/page_progress_pill during layout: running second layout pass"），这一段的已测量宽高
     * 就停在**上一段文字**的尺寸上；而它是 wrap_content、没有 singleLine / maxLines，新文字装不下
     * 就会折到第二行，第二行落在胶囊盒子之外被裁 —— 表现成「2 /」、分母不见了，而且要等下一次
     * 真正的排版才复原（有时一直不复原）。
     *
     * **显隐同理，而且更隐蔽**：胶囊行 [top_end_pill_row] 是 wrap_content，读数段从 GONE 变
     * VISIBLE 时若这次 requestLayout 也被吞，行就一直停在「只有收起」那个宽度上，读数段是 0 宽
     * —— 屏幕上什么都没有。之后也不会自愈：文字没再变，[refreshPageProgressPill] 的早退让
     * setText 不再触发排版，滑多远都还是 0 宽。
     *
     * 「进页即展开」（设置里的「多图自动展开」）正好踩在这个组合上：绑定期间
     * [showCollapsePill] 先把「收起」点亮、胶囊行按「只有收起」量过一遍，读数段随后才第一次
     * 露出来 —— 真机实测行宽停在 239px（收起 + 两侧 12dp），读数段 0 宽，永不出现。折叠态进页
     * 不会：那时整行还是 GONE，行与读数段是在同一次「露出来」里一起被量的。
     *
     * @param text 要显示的读数；null = 收起读数段（单图 / 动图，或滑到简介 / 评论 / 相关作品）。
     */
    private fun applyPageProgressPill(pill: TextView, text: String?) {
        val visible = text != null
        if (pill.isVisible == visible && (text == null || pill.text?.toString() == text)) return
        val apply = {
            if (text != null && pill.text?.toString() != text) pill.text = text
            if (pill.isVisible != visible) pill.isVisible = visible
            // 胶囊本体与那 10dp 间隔都跟着这两段走，必须在这两步之后再算。
            if (_chromeBind != null) syncTopEndPill()
        }
        // 还没上屏就没有排版在跑，当场写；也免得把 runnable 丢进 RunQueue 一直挂着。
        if (!pill.isAttachedToWindow) {
            apply()
            return
        }
        // 上屏之后一律推迟：显隐与文字一起改，读数段「露出来」时才带着新文字被重新量一次
        // （分两步改会先露一帧空胶囊，或先露一帧上一次的读数）。
        pill.post { apply() }
    }

    /**
     * 阅读胶囊**长按** → 多图预览(#1085)。web 端那枚页码按钮是单击预览的,这里改成长按:胶囊里 「收起」那一段本来就吃单击,再叠一层单击必然互抢。
     *
     * 监听必须挂两处 —— 胶囊本体和里面的「收起」。「收起」自己 clickable,触摸落在它身上就被它
     * 吃掉了,父容器根本收不到;只挂父的话,展开态(也就是最想预览的时候)按在「收起」上没反应。
     *
     * 选页结果走 fragment result 回来而不是 lambda:sheet 跨横屏会重建,回调必然失效(同 #1023)。
     */
    private fun attachPagesPreview() {
        val onLongPress = View.OnLongClickListener { v ->
            if (!openPagesPreview()) return@OnLongClickListener false
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            true
        }
        chromeBind.topEndPillRow.setOnLongClickListener(onLongPress)
        chromeBind.collapsePill.setOnLongClickListener(onLongPress)
        childFragmentManager.setFragmentResultListener(
            ArtworkThumbsSheet.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            jumpToPage(bundle.getInt(ArtworkThumbsSheet.KEY_PAGE_INDEX))
        }
    }

    /** 弹预览。返回是否真的弹了 —— 没弹就别把这次长按吃掉。 */
    private fun openPagesPreview(): Boolean {
        val illust = currentIllust() ?: return false
        if (illust.isGif()) return false
        val models =
            if (isSnapshotMode) {
                // 快照页的图在本地,缩略图别回网上取(离线打开时那边什么也拿不到)。
                val data = snapshotId?.let { SnapshotRuntimeCache.get(it) } ?: return false
                ArtworkThumbsSheet.localModels(illust.page_count) { data.pageFile(it) }
            } else {
                ArtworkThumbsSheet.networkModels(illust)
            }
        return ArtworkThumbsSheet.show(
            this,
            models,
            pageProgressIndex.coerceAtLeast(0),
            readerIllustId = illust.id.takeUnless { isSnapshotMode },
        )
    }

    /**
     * 跳到预览里选中的那一页。折叠态(3P+ 未展开)下列表里只有 p0,先展开 —— 预览里点的多半正是 被折起来的那些页。展开产出的条目走 feedViewModel 的异步
     * diff,落地后由 [drainPendingPageJump] 接着滚。
     */
    private fun jumpToPage(index: Int) {
        commentsJumpRealign = false
        pendingCollapseResetScroll = false
        if (scrollToPageItem(index)) return
        val collapsible = pageAdapter as? CollapsibleIllustAdapter ?: return
        if (collapsible.isExpanded) return
        pendingPageJump = index
        collapsible.expand()
    }

    /**
     * 收二级大图翻页落定的广播（[ViewerPageLink]），把列表视口滚到同一页。
     *
     * 挂在 STARTED 上：大图是透明窗口，本页在它之下只走 onPause、仍是 STARTED，所以收集器全程
     * 活着；而 STARTED 又能保证「切后台 / 离开」时立刻停手。
     *
     * 两条门禁：
     * - 「仅已展开时」只认**展开态**：折叠态列表里只有 p0，跳过去也没有那一页；「自动展开并跟随」
     *   才替用户展开（用户显式选的档位）。
     * - `page == entryPage` 只在**先前真被挪动过**时才跟着回。否则「点开又原页退出」这种没翻过页的
     *   会话会被无谓地顶到顶对齐，破坏用户自己滚出来的位置。
     */
    private fun wireViewerPageLink() {
        if (tabletStage != null) return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ViewerPageLink.pings.collect { ping ->
                    if (ping.illustId != viewerLinkIllustId) return@collect
                    // 「仅已展开时」只在**真的折叠着**时拦。判 isCollapsed 而不是 !isExpanded：
                    // 2P / 动图 / 快照 ≤2P 这些页面根本没有折叠态（adapter 压根不是
                    // CollapsibleIllustAdapter），不该被这一档挡住 —— `?.` 得 null 即不拦。
                    // 「自动展开并跟随」不拦，交给 jumpToPage 自己走「先展开、条目落地后补跳」那条路。
                    if (Shaft.sSettings.getViewerViewportLinkMode() ==
                        Settings.VIEWER_VIEWPORT_LINK_EXPANDED_ONLY &&
                        (pageAdapter as? CollapsibleIllustAdapter)?.isCollapsed == true
                    ) {
                        return@collect
                    }
                    if (ping.page == ping.entryPage && !viewerViewportSynced) return@collect
                    viewerViewportSynced = true
                    viewerSyncedPage = ping.page
                    jumpToPage(ping.page)
                }
            }
        }
    }

    /**
     * 把「当前页那一格」的屏幕矩形回传给大图，供退出时归位。
     *
     * 挂在列表的排版回调上（与 [refreshPageProgressPill] 同源）：`scrollToPositionWithOffset` 只是
     * 设下待定位置，要等这一帧排版跑完那一格才有真实坐标。矩形与列表可视区求交 —— 高图的整格
     * 远超屏幕，取可见段才不至于把归位落点算到屏幕外（宽度不变，缩放比例仍然正确）。
     *
     * `viewerSyncedPage < 0`（没进过大图）时直接返回，所以平时每帧滚动不付这份开销。
     */
    private fun publishViewportRect() {
        val page = viewerSyncedPage
        if (page < 0 || _chromeBind == null || tabletStage != null) return
        val fa = feedAdapter ?: return
        val listView = feedBinding.feedListView
        val lm = listView.layoutManager ?: return
        val pos = fa.currentList.indexOfFirst { it is ArtworkPageItem && it.pageIndex == page }
        val cell = if (pos >= 0) lm.findViewByPosition(pos) else null
        if (cell == null) {
            // 还没排到这一格也要回传（rect 给 null）：告诉大图「详情页已挪动」，它就退回淡出，不缩向失准的进场矩形。
            ViewerPageLink.publishViewport(viewerLinkIllustId, page, null)
            return
        }
        val cellLoc = IntArray(2)
        cell.getLocationOnScreen(cellLoc)
        val listLoc = IntArray(2)
        listView.getLocationOnScreen(listLoc)
        val left = cellLoc[0]
        val top = maxOf(cellLoc[1], listLoc[1])
        val right = cellLoc[0] + cell.width
        val bottom = minOf(cellLoc[1] + cell.height, listLoc[1] + listView.height)
        ViewerPageLink.publishViewport(
            viewerLinkIllustId,
            page,
            if (right > left && bottom > top) intArrayOf(left, top, right, bottom) else null,
        )
    }

    /** 那一页已经在列表里就滚过去并返回 true;还被折着(找不到条目)返回 false。 */
    private fun scrollToPageItem(index: Int): Boolean {
        if (_chromeBind == null) return false
        val fa = feedAdapter ?: return false
        val pos = fa.currentList.indexOfFirst { it is ArtworkPageItem && it.pageIndex == index }
        if (pos < 0) return false
        // 落位对齐列表顶缘,与页码读数的锚线口径一致:浮标压着谁就读谁,跳完读数正好是这一页。
        feedBinding.feedListView.scrollToPositionWithOffset(pos, 0)
        return true
    }

    /**
     * 展开产出的条目落地后补跳。必须排到下一帧:条目增删通知先于这一帧的排版到达,当场滚会落在 上一帧的位置上(与 [scheduleCommentsRealign] 同一个理由)。 ⚠️
     * 先验视图还活着 —— 在飞的 submitList 可能在 onDestroyView 之后才派发。
     */
    private fun drainPendingPageJump() {
        val index = pendingPageJump ?: return
        if (_chromeBind == null) {
            pendingPageJump = null
            return
        }
        feedBinding.feedListView.post {
            if (pendingPageJump != index || _chromeBind == null) return@post
            if (scrollToPageItem(index)) pendingPageJump = null
        }
    }

    /**
     * 收起欠着的那次回顶。
     *
     * 必须等「隐藏页被移除」的通知到了才发,而且是**当场设 pending scroll、不 post**:
     * `submitList` 的 diff 在后台线程算,它的增删通知先于这一帧的排版到达;这里设下的
     * pending position 会和那次移除被同一次 layout 一起消费——回顶与收起因此落在同一帧。
     *
     * 反过来,在点击那一刻就滚(原先的做法)会先滚在一份还没缩短的旧内容上:用户先看到顶部的
     * 旧页,下一帧它才被标题 / 作者替掉,就是那半屏闪。
     */
    private fun drainCollapseResetScroll() {
        if (!pendingCollapseResetScroll) return
        // 其他区块也会移除条目；只有折叠列表真正落地才能消费这次回顶。
        if ((pageAdapter as? CollapsibleIllustAdapter)?.isCollapsed != true) return
        if (feedAdapter?.currentList?.any { it is ArtworkPageItem && it.pageIndex > 0 } != false) return
        pendingCollapseResetScroll = false
        // 在飞的 submitList 可能在 onDestroyView 之后才派发,那时候 feedBinding 已经不在了。
        if (_chromeBind == null) return
        val listView = feedBinding.feedListView
        // 收起回顶时位置 0 往往还在屏上、且已滑出顶部:SGLM 会把
        // paddingTop - getDecoratedStart(item0)(正数)当锚点偏移,经 cacheReferenceLineAndClear 写进
        // 各列的参考行,某一列于是拿到正的起排 y(下一代数据进来时那一列顶部空出一块)。只有这种情形
        // 才需要重建列状态;列表本来就在顶部时偏移是 0,位置 0 已回收时 SGLM 走 mInvalidateOffsets
        // 的干净分支,都不用。
        val manager = listView.layoutManager
        if (manager is StaggeredGridLayoutManager) {
            val firstVisible = manager.findViewByPosition(0)
            // 竖向 SGLM 里 getDecoratedStart(item0) 等于 getDecoratedTop(item0) - topMargin
            //（getDecoratedStart 是 OrientationHelper 的方法，LayoutManager 上没有）。
            val firstStart = firstVisible?.let {
                manager.getDecoratedTop(it) -
                    ((it.layoutParams as? RecyclerView.LayoutParams)?.topMargin ?: 0)
            }
            if (firstStart != null && firstStart < listView.paddingTop) {
                // 重建手段:setSpanCount 是 SGLM 唯一会 new Span[] 的公开口,抖一次列数即可把各列行
                // 缓存与 span 分配一起清零。动画进行中重建会让内部计数对不上(子视图被清空的空白屏),
                // 所以先摘掉 animator,下一帧再装回。
                val animator = listView.itemAnimator
                if (animator != null) {
                    listView.itemAnimator = null
                }
                val spanCount = manager.spanCount
                manager.setSpanCount(spanCount + 1)
                manager.setSpanCount(spanCount)
                if (animator != null) {
                    listView.post { listView.itemAnimator = animator }
                }
            }
        }
        listView.scrollToPositionWithOffset(0, 0)
    }

    /**
     * 胶囊本体的显隐:「收起」和读数是同一枚胶囊里的两段,任一段有内容就露出来;两段都在场时 才插进那 10dp 的间隔 [top_end_pill_gap]。
     *
     * 间隔用一个独立的 Space 而不是挂成谁的 margin:LinearLayout 里 GONE 的子 View 自己不占位, 但它**前一个可见兄弟**的 marginEnd
     * 照常生效 —— 挂在任一段身上,都会在另一段隐藏时让胶囊 往那一侧多出 10dp、左右不对称。
     *
     * 整页屏蔽遮罩盖着时一律收起 —— 胶囊的 elevation(8dp)高过遮罩(0),同一个父容器先按 Z 排序再按声明顺序画,不挡就会出现「全页糊掉、右上角还飘着 4 / 10」,与
     * [showFabBar] 同一条规则。
     */
    private fun syncTopEndPill() {
        val hasCollapse = chromeBind.collapsePill.isVisible
        val hasProgress = chromeBind.pageProgressPill.isVisible
        chromeBind.topEndPillGap.isVisible = hasCollapse && hasProgress
        chromeBind.topEndPillRow.isVisible = (hasCollapse || hasProgress) && !muteMaskActive
    }

    /**
     * 页码浮标的刷新时机挂在列表的排版回调上,而不是 adapter 的增删通知:后者先于这一帧的 layout 到达,那时候子 View 的 top/bottom
     * 还是上一帧的,算出来是错的。排版回调则顺带 覆盖了「图加载完撑高条目」这类没有滚动、也没有条目增删的位移。
     *
     * 挂和摘都锚在「附着到窗口」上,不能放到 onDestroyView 里摘:FragmentManager 是先把 view 从容器里 removeView(已
     * detach、mAttachInfo 置空)、再走 onDestroyView 的,那时候 `getViewTreeObserver()` 返回的已经是一份新建的游离
     * observer,remove 静默落空 —— 监听会一直留在**窗口**那份上,每次 layout 空跑一遍还钉着已销毁的 Fragment。详情页在 ViewPager
     * 里翻一路,就是一路这样的僵尸监听。
     */
    private fun attachPageProgressPill() {
        val listView = feedBinding.feedListView
        // 同一趟排版回调顺带对齐自动快照的视口判据：从上层页返回、回退栈重显时列表可能就停在
        // 相关作品那一段，没有任何滚动事件，只有排版回调能发现「已经不是在看这个作品了」。
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            refreshPageProgressPill()
            syncAutoSnapshotViewport()
            publishViewportRect()
        }
        listView.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
                }

                // detach 派发时 mAttachInfo 还没置空,这里拿到的仍是窗口那份,摘得掉。
                override fun onViewDetachedFromWindow(v: View) {
                    v.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
                }
            }
        )
        // onViewCreated 时 view 通常已经在容器里了,附着回调不会再补发一次。
        if (listView.isAttachedToWindow) {
            listView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        }
    }

    // ── 懒加载区块 ───────────────────────────────────────────────────────────
    // 各区块「怎么拉 + 怎么把数据落回条目」全在 [ArtworkSection];这里只把 renderer 的
    // 「区块可见」信号转给 [SectionLoader](去重 + 单飞 + 视图作用域)。进页时区块还没
    // 滚到,一律不触发——池里已有完整 illust 时点进详情不会发任何多余请求。

    /** renderer holder attach 且数据仍空时调用:区块首次上屏触发一次懒加载。 */
    internal fun onSectionVisible(section: ArtworkSection) {
        sectionLoader?.onVisible(section)
    }

    /** 联网后补拉加载失败的区块。区块的触发信号只有 holder 的 attach，用户如果就停在 那一屏不动（评论/相关区块正在转圈时最常见），不补这一下就再也没有重试时机。 */
    override fun onNetworkRestored() {
        super.onNetworkRestored()
        sectionLoader?.retryFailed()
    }

    /**
     * 把池里那条 bean 的 caption 同步到简介块。首屏是拿列表 bean 直接画的(不为了简介去阻塞, 见 [ArtworkV3ViewModel] 的 caption
     * 补拉),所以简介可能**晚到**、也可能一开始压根没这条目。
     *
     * 只做「补上 / 换内容」,不做「抹掉」:池会被各种精简来源覆盖(作者其他作品、相关作品列表都会 合池),拿一次空 caption 去删已经显示出来的简介,就成了简介闪一下又没了。
     *
     * 简介块一旦在页面里,信息区那个补位的翻译按钮必须同时收起,见 [hideHeroTranslate]。
     *
     * 位置锚在 [ArtworkTagsItem] 之前——对齐 [ArtworkV3FeedSource.buildArtworkHeaderItems] 的 区块顺序(Hero
     * /(Series)/ Artist / Desc / Tags / ...)。tags 块是无条件产出的,锚点稳定。 没变化时原样返回同一个
     * list,[FeedViewModel.mutateItems] 据此判定 no-op,所以这个观察者 每次因收藏 / 关注变更 fire 都是免费的。
     *
     * ⚠️ 这条插入可能与 fling 同帧落地(简介比首屏晚 ~0.5s,用户很可能正在滚)。它安全的前提是 [onListReady] 把 itemAnimator 关掉了 —— 没有
     * itemAnimator 就不跑 SGLM 的 predictive 预布局,也就绕开了 [ceui.lisa.helper.StaggeredManager]
     * 注释里那个「fling + 插入同帧」的 AOSP 越界。谁要把动画开回来,先想清楚这里。
     */
    private fun syncDescSection(caption: String?, title: String?) {
        // 门槛只看 caption,和 [ArtworkV3FeedSource.buildArtworkHeaderItems] 的产出条件一致:
        // 放宽成「标题非空也补入」的话,无简介的作品会从这条后台补入的路径长出一个空简介块。
        if (caption.isNullOrEmpty()) return
        val descCaption = caption
        val descTitle = title.orEmpty()
        feedViewModel.mutateItems { items ->
            val withDesc = run {
                val at = items.indexOfFirst { it is ArtworkDescItem }
                if (at >= 0) {
                    if (
                        (items[at] as ArtworkDescItem).caption == descCaption &&
                            (items[at] as ArtworkDescItem).title == descTitle
                    ) {
                        items
                    } else {
                        items.toMutableList().apply {
                            this[at] = ArtworkDescItem(descCaption, descTitle)
                        }
                    }
                } else {
                    val anchor = items.indexOfFirst { it is ArtworkTagsItem }
                    if (anchor < 0) {
                        items // header 还没建出来(首屏仍在飞),等下一次 fire
                    } else {
                        Timber.tag(ARTWORK_LAZY_TAG)
                            .d("简介块后台补入 illustId=%d len=%d", illustId, descCaption.length)
                        items.subList(0, anchor) +
                            ArtworkDescItem(descCaption, descTitle) +
                            items.subList(anchor, items.size)
                    }
                }
            }
            hideHeroTranslate(withDesc)
        }
    }

    /**
     * 简介块已经在页面里 → 收起信息区那个补位的翻译按钮([ArtworkHeroItem.showTranslate]), 否则同屏两个翻译入口。
     *
     * 必须在这里收:hero 条目建出来时用的是**列表页那条**空 caption 的 bean,而简介是
     * [ceui.pixiv.ui.detail.ArtworkV3ViewModel.ensureTrustedCaption] 回源拿**另一个** bean 实例补回来的;hero
     * 条目自己既没被替换、[ArtworkHeroItem.equals] 又只比实例身份, 不主动翻这一下的话它永远重绑不了(#960 的补拉在动态流里约一半会命中)。
     *
     * 没得可收时原样返回同一个 list,守住 [ceui.pixiv.feeds.FeedViewModel.mutateItems] 的 「同一个 list == no-op」约定 ——
     * 这个观察者每次收藏 / 关注变更都会 fire。
     */
    private fun hideHeroTranslate(items: List<FeedItem>): List<FeedItem> {
        val at = items.indexOfFirst { it is ArtworkHeroItem && it.showTranslate }
        if (at < 0) return items
        val hero = items[at] as ArtworkHeroItem
        return items.toMutableList().apply {
            this[at] = ArtworkHeroItem(hero.illust, showTranslate = false)
        }
    }

    /**
     * issue #1023: 用户就地编辑完标签后重绑标签区块。改的是池里那条 bean 的 tags(同一个实例), 上面那条 [ObjectPool] 观察者只管简介 / 屏蔽 /
     * 关注,不碰标签,所以只能靠换一个新的 [ArtworkTagsItem] 让 DiffUtil 看见 —— 新旧的 tagSignature 不同即重绑。
     *
     * [FeedViewModel.updateItems] 走的是默认 structural=true,这里会顺带 bump 一次
     * structureVersion(条目数其实没变)。一次编辑一次、详情页统共几十条,不值得为此另开一个 非结构化的更新入口。
     */
    internal fun refreshTagsSection() {
        // 编辑弹窗活得比本 Fragment 长(横屏重建 / 被顶掉),回调回来时可能已经 detach。
        if (!isAdded) return
        // 必须回池现取那条 bean,不能沿用条目里那份:池对非完整版更新走 mergeKeepingExisting,
        // 会把存的换成一个**新实例**(「作者其他作品」的响应经 Mapper 合池就会触发,而那个区块
        // 就在标签行下面、用户多半已经滚过),此后条目手里的是旧实例。PixivTagEditOperate 改的
        // 是池里的当前实例,拿旧实例重建则 tagSignature 原封不动,编辑成功了标签行却不动。
        val illust = ObjectPool.getIllust(illustId).value ?: return
        feedViewModel.updateItems<ArtworkTagsItem> { ArtworkTagsItem(illust) }
    }

    /**
     * 收起超长简介后把简介块拉回视口顶部(#965)。收起按钮在简介**末尾**,长简介收起时 视口锚点还停在原来的绝对偏移,块一缩几千像素,画面就跳到更下面的区块去了;
     * 简介顶部仍在屏内(短简介)时无此问题,不动。
     */
    internal fun scrollDescBackIntoView(itemView: View) {
        if (itemView.top >= 0) return
        val rv = feedBinding.feedListView
        val pos = rv.getChildAdapterPosition(itemView)
        if (pos == RecyclerView.NO_POSITION) return
        rv.scrollToPositionWithOffset(pos, 0)
    }

    /**
     * 一键跳到评论预览区(#970)。评论条目随首屏 header 一并产出(见 [ArtworkV3FeedSource.buildArtworkHeaderItems]),首屏还在飞时
     * pos 找不到,静默不动。 offset 用悬浮顶栏实际底缘,让区块落在 toolbar 之下而不是被它盖住;落位后区块 attach 自然触发评论懒加载。
     */
    private fun scrollToCommentsSection() {
        val fa = feedAdapter ?: return
        val pos = fa.currentList.indexOfFirst { it is ArtworkCommentsItem }
        if (pos < 0) return
        feedBinding.feedListView.scrollToPositionWithOffset(pos, chromeBind.topOverlayColumn.bottom)
        commentsJumpRealign = true
    }

    /**
     * 数据变更后把基线校正排到下一帧(布局落定后再量);flag 不亮时零开销。 ⚠️ 必须先验视图还活着再碰 feedBinding:FeedAdapter 的 diff
     * 是异步的,在飞的一次 submitList 可能在 onDestroyView 之后才派发到旧 adapter 的 observer。
     */
    private fun scheduleCommentsRealign() {
        if (!commentsJumpRealign || _chromeBind == null) return
        feedBinding.feedListView.post { alignCommentsIfPending() }
    }

    /**
     * 基线收敛一步:评论块顶不在 [FragmentArtworkV3Binding.topOverlayColumn] 底缘就再锚一次;
     * 已在基线上且列表还能继续下滚(下方内容已够,end-gap 修正不会再动它)才算收敛完毕。
     */
    private fun alignCommentsIfPending() {
        if (!commentsJumpRealign || _chromeBind == null) return
        val fa = feedAdapter ?: return
        val pos = fa.currentList.indexOfFirst { it is ArtworkCommentsItem }
        if (pos < 0) return
        val rv = feedBinding.feedListView
        val target = chromeBind.topOverlayColumn.bottom
        val vh = rv.findViewHolderForAdapterPosition(pos)
        if (vh != null && vh.itemView.top == target) {
            if (rv.canScrollVertically(1)) commentsJumpRealign = false
            return
        }
        rv.scrollToPositionWithOffset(pos, target)
    }

    private fun attachArtistFollowObserver(authorId: Long) {
        if (authorId <= 0L || authorId == artistObservedUserId) return
        artistObservedUserId = authorId
        // 关注了没：统一观察 ObjectPool 的 User。
        ObjectPool.get<ceui.loxia.User>(authorId).observe(viewLifecycleOwner) {
            refreshArtistFollowItem()
        }
        // 怎么关的：FollowVisibility。两条渠道缺一不可 —— 画师主页拿 user/follow/detail 补上
        // 「原来是私密关注」时 is_followed 一个字节都没变，上面那条不会响（issue #997 追加反馈）。
        FollowVisibility.changes.observe(viewLifecycleOwner) { changed ->
            if (changed == authorId) refreshArtistFollowItem()
        }
    }

    private fun refreshArtistFollowItem() {
        feedViewModel.updateItems<ArtworkArtistItem> { item ->
            val followed = ArtworkArtistItem.resolveIsFollowed(item.illust)
            val private = ArtworkArtistItem.resolvePrivateFollow(item.illust)
            if (item.isFollowed == followed && item.isPrivateFollow == private) {
                item
            } else {
                ArtworkArtistItem(item.illust, followed, private)
            }
        }
    }

    // ── 悬浮下载 / 收藏胶囊 ─────────────────────────────────────────────────────

    private fun setupFabBar() {
        fabBarController.applyPalette(palette)

        // 一键跳转评论区(#970):默认关,设置「看图与详情」里手动打开;评论区本身被关掉时无处可跳,一并不放出。
        if (Shaft.sSettings.isArtworkV3ShowComments && Shaft.sSettings.isArtworkV3ShowCommentJumpFab) {
            fabBarController.setCommentJumpVisible(true)
            chromeBind.fabBar.fabComment.setOnClick { scrollToCommentsSection() }
            // 长按直达完整评论区(#1009),与下载/收藏 FAB 的长按增强同一套习惯。
            chromeBind.fabBar.fabComment.setOnLongClickListener {
                val intent = Intent(requireContext(), TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.COMMENTS.key)
                intent.putExtra(Params.ILLUST_ID, illustId)
                startActivity(intent)
                true
            }
        }

        artworkViewModel.isBookmarked.observe(viewLifecycleOwner) { bookmarked ->
            fabBarController.setBookmarked(bookmarked)
        }
        artworkViewModel.downloadFabState.observe(viewLifecycleOwner) { state ->
            if (view == null) return@observe
            fabBarController.renderDownload(state)
        }
        // 网页 ajax 的每页真实宽高到达 → 喂给顶部大图 adapter,预置各页展示 ratio(下载前就摆准高度)。
        // adapter 懒建:值先到就由 ensurePageAdapter 补,adapter 先建就由这里补——两序都覆盖。
        artworkViewModel.pageDimensions.observe(viewLifecycleOwner) { dims ->
            pageAdapter?.seedPageDimensions(dims)
        }
    }

    /**
     * 整页遮罩盖上 / 揭掉时同步底部胶囊（下载 / 收藏 / 评论）。
     *
     * 遮罩本该盖住胶囊——`fab_bar` 在 `abandoned_frame` **之前**声明。但 view_v3_fab_bar 的根 带
     * `android:elevation="12dp"`，遮罩是 0，而同一个父容器**先按 Z 排序、再按声明顺序**画，
     * 于是胶囊浮在糊掉的图上：屏蔽了的作品照样能一键收藏、下载、跳评论，屏蔽等于只糊了张图。 别改成给遮罩提 elevation —— 那只挡住「看见」，胶囊仍在底下响应点击。
     *
     * 用一个状态位挡在 [showFabBar] 里，而不是就地 hide 一次：列表滚动监听会在上滑时把胶囊 放回来（`onScrolled` →
     * [showFabBar]），遮罩底下的列表虽然点不到、fling 惯性和程序滚动 仍会走那条回调，单靠一次 hide 挡不住。
     *
     * 幂等（值没变直接返回）是必须的：本 observer 在**没被屏蔽**时也照常发射，每次都调 [showFabBar] 会把用户下滑收起的胶囊硬顶回来。
     */
    private fun setMuteMaskActive(active: Boolean) {
        if (muteMaskActive == active) return
        muteMaskActive = active
        syncTopEndPill()
        if (active) {
            hideFabBar(immediate = true)
        } else {
            showFabBar()
        }
    }

    private fun hideFabBar(immediate: Boolean = false) {
        if (!fabShown && !immediate) return
        fabShown = false
        val fabBar = chromeBind.fabBar.root
        fabBar.animate().cancel()
        val hiddenTranslation = fabBar.height + 100f
        if (immediate) {
            fabBar.translationY = hiddenTranslation
            fabBar.alpha = 0f
            fabBar.visibility = View.INVISIBLE
        } else {
            fabBar.visibility = View.VISIBLE
            fabBar
                .animate()
                .translationY(hiddenTranslation)
                .alpha(0f)
                .setDuration(FAB_ANIMATION_DURATION_MS)
                .withEndAction { if (!fabShown) fabBar.visibility = View.INVISIBLE }
                .start()
        }
    }

    private fun showFabBar() {
        if (composerHost?.active == true) return // 内联输入栏浮着时不放回胶囊
        if (muteMaskActive) return // 整页屏蔽遮罩盖着时同理，见 setMuteMaskActive
        if (fabShown) return
        fabShown = true
        val fabBar = chromeBind.fabBar.root
        fabBar.animate().cancel()
        fabBar.visibility = View.VISIBLE
        fabBar.animate().translationY(0f).alpha(1f).setDuration(FAB_ANIMATION_DURATION_MS).start()
    }

    // ── 底部内联评论输入栏(见 ArtworkCommentComposerHost) ─────────────────────────

    /** 评论区「留下你的评论吧」入口(由 commentsRenderer 调)。 */
    internal fun showComposer() {
        composerHost?.show()
    }

    private fun applySentComment(result: SentComment) {
        val (parentCommentId, comment) = result
        // 内联只发顶层评论;插到预览区最前
        if (parentCommentId <= 0L) {
            feedViewModel.updateItems<ArtworkCommentsItem> { it.prepend(comment) }
        }
        composerHost?.dismiss()
    }

    // ── inset ──────────────────────────────────────────────────────────────

    /**
     * 平板排版（layout-sw600dp + 「平板适配排版」开着，#1087）：作品进舞台，列表 / 胶囊在信息栏里。
     * 返回与更多菜单挪到舞台左右上角，信息栏不再有顶栏。快照模式的页面数据来自快照本身、
     * 关掉开关的平板，都按手机排版把图放在列表里（舞台收起、信息栏铺满）。
     */
    private fun setUpTabletStage() {
        // 手机布局没有这几个 view：tabletStage 保持 null，但仍要对齐图片条目（见 reconcilePageItems）
        val stage = chromeBind.tabletStage
        val infoColumn = chromeBind.tabletInfoColumn
        val backdrop = chromeBind.tabletBackdrop
        val scrim = chromeBind.tabletBackdropScrim
        if (stage == null || infoColumn == null || backdrop == null || scrim == null) {
            // 手机排版
        } else if (!usesTabletStage()) {
            stage.isVisible = false
            backdrop.isVisible = false
            scrim.isVisible = false
        } else {
            tabletStage = ArtworkTabletStage(
                fragment = this,
                stage = stage,
                infoColumn = infoColumn,
                backdrop = backdrop,
                scrim = scrim,
                forceOriginal = { artworkViewModel.forceOriginalPreview },
                onBack = { requireActivity().finish() },
                onMore = { chromeBind.navMore.performClick() },
                onOpenReader = { openComicReader() },
            )
            // 返回 / 更多都在舞台上；信息栏不要顶栏，标题直接从栏顶开始
            chromeBind.toolbar.isVisible = false
            ObjectPool.get<Illust>(illustId).value?.let { tabletStage?.bind(it) }
        }
        reconcilePageItems()
    }

    /**
     * 列表里「有没有作品图条目」要和当前排版一致。数据源按建 VM 时的排版决定出不出图，而 VM
     * 会跨配置变化存活：折叠屏开合、平板分屏缩窄会跨过 sw600dp，这时补上或拿掉图片条目。
     * 手机永远不跨，一致时是 no-op。
     */
    private fun reconcilePageItems() {
        if (isSnapshotMode) return
        val staged = tabletStage != null
        feedViewModel.mutateItems { items ->
            val hasPages = items.any { it is ArtworkPageItem || it is ArtworkUgoiraItem }
            when {
                staged && hasPages ->
                    items.filterNot { it is ArtworkPageItem || it is ArtworkUgoiraItem }
                !staged && !hasPages && items.any { it is ArtworkHeroItem } -> {
                    val illust = ObjectPool.get<Illust>(illustId).value
                    if (illust == null) items else ArtworkV3FeedSource.buildArtworkPageItems(illust) + items
                }
                else -> items
            }
        }
    }

    private fun handleSystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(chromeBind.toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            // 宽窗口上下排布时信息栏从半屏处开始，顶栏不贴状态栏
            val top = if (tabletStage?.sideBySide == false) 0 else insets.top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            windowInsets
        }
        // 胶囊底距(导航栏 inset + 24dp)与二级大图页共用同一套逻辑,保证两页落点一致
        fabBarController.attachBottomInsetMargin()
        // 列表铺到屏幕最底,底 padding = navBar inset 让末条停在导航栏之上(clipToPadding=false 已设)
        val listView = feedBinding.feedListView
        listView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            // 宽窗口列表第一项是标题而不是大图：首帧布局前就让出状态栏（并排时信息栏贴屏幕顶）
            // 和一点呼吸空间（布局后再加的话，clipToPadding=false 的列表会把第一项留在原位）
            val top = tabletStage?.let { stage ->
                val status = if (stage.sideBySide) {
                    windowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                } else {
                    0
                }
                status + 16.ppppx
            } ?: v.paddingTop
            v.setPadding(v.paddingLeft, top, v.paddingRight, insets.bottom)
            windowInsets
        }
        // 右上角胶囊行(页码浮标 + 「收起」)钉在顶栏(toolbar + 可选重试横幅)之下(见 #881)
        val pillGap = 8.ppppx
        chromeBind.topOverlayColumn.addOnLayoutChangeListener {
            _,
            _,
            _,
            _,
            bottom,
            _,
            _,
            _,
            oldBottom ->
            if (bottom == oldBottom || _chromeBind == null) return@addOnLayoutChangeListener
            val row = chromeBind.topEndPillRow
            val lp = row.layoutParams as FrameLayout.LayoutParams
            val target = bottom + pillGap
            if (lp.topMargin != target) {
                lp.topMargin = target
                row.layoutParams = lp
            }
        }
    }

    // ── toolbar / 悬浮胶囊 / more 菜单(点击行为见 ArtworkV3Actions) ─────────────────

    private fun setupNavBar() {
        chromeBind.toolbar.setNavigationOnClickListener { requireActivity().finish() }

        // 胶囊位置 + 下载 / 收藏顺序偏好;靠边时离屏幕边 20dp,与二级大图页那一行的左右留白一致
        fabBarController.applyLayoutPreference(sideMargin = 20.ppppx)

        // 下载 / 收藏的点击、长按与下载完成广播
        actions?.bindFabBar()

        chromeBind.navMore.setOnClick { actions?.showMoreMenu() }
    }

    /**
     * 简介区下载按钮(#1052):只下载/导出简介文本,不触发作品图片下载。 放在 Fragment 而不是 renderer 里,因为 [illustId] 是
     * private,renderer 拿不到。
     *
     * bean 走 [currentIllust]:简介区块在快照里照常渲染、按钮也照常绑(见 ArtworkSectionRenderers 的 descRenderer),而快照的
     * illustId 是 0L —— 按 id 读池会拿到 null 然后**静默** return,点了什么都不发生(其余快照里不支持的控件至少还会 toast)。
     */
    internal fun downloadDescCaption() {
        val illust = currentIllust() ?: return
        lifecycleScope.launch {
            val ok = IllustCaptionExporter.exportManual(illust)
            if (ok) {
                Common.showToast(R.string.string_181)
            } else {
                Common.showToast(R.string.update_download_failed)
            }
        }
    }

    /**
     * 只给非 fullSpan 条目(相关卡片)加间距;顶部大图页 + header 区块(fullSpan)零 offset。 对齐 legacy 的
     * RelatedOnlySpaceDecoration。
     */
    private class RelatedOnlySpaceDecoration(
        private val space: Int,
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val lp = view.layoutParams
            if (lp !is StaggeredGridLayoutManager.LayoutParams || lp.isFullSpan) return
            val spanCount = (parent.layoutManager as? StaggeredGridLayoutManager)?.spanCount ?: 1
            outRect.bottom = space
            val spanIndex = lp.spanIndex
            outRect.left = if (spanIndex == 0) space else space / 2
            outRect.right = if (spanIndex == spanCount - 1) space else space / 2
        }
    }

    private fun resolveCurrentViewportPage(): Int {
        if (pageProgressIndex >= 0) {
            return pageProgressIndex
        }
        val count = currentPageCount()
        if (count <= 1) return 0
        val listView = feedBinding.feedListView
        val items = feedAdapter?.currentList ?: return 0
        val lm = listView.layoutManager ?: return 0
        val anchorY = chromeBind.topOverlayColumn.bottom
        var current = -1
        var firstVisible = -1
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i)
            val item = items.getOrNull(lm.getPosition(child)) as? ArtworkPageItem ?: continue
            if (firstVisible < 0 || item.pageIndex < firstVisible) firstVisible = item.pageIndex
            if (child.top <= anchorY && item.pageIndex > current) current = item.pageIndex
        }
        val target = if (current >= 0) current else firstVisible
        return target.coerceIn(0, (count - 1).coerceAtLeast(0))
    }

    private fun findArtworkPageViewHolder(pageIndex: Int): Pair<Int, ViewHolder<RecyIllustDetailBinding>?>? {
        val fa = feedAdapter ?: return null
        val pos = fa.currentList.indexOfFirst { it is ArtworkPageItem && it.pageIndex == pageIndex }
        if (pos < 0) return null
        val cell = feedBinding.feedListView.findViewHolderForAdapterPosition(pos) ?: return (pos to null)
        val binding = (cell as? FeedCell<*, *>)?.binding as? RecyIllustDetailBinding
            ?: (cell as? ViewHolder<*>)?.baseBind as? RecyIllustDetailBinding
        val holder = if (binding != null) ViewHolder(binding) else null
        return (pos to holder)
    }

    private fun isArtworkPageInViewport(pageIndex: Int): Boolean {
        if (tabletStage != null) {
            return tabletStage?.currentPage == pageIndex
        }
        val fa = feedAdapter ?: return false
        val pos = fa.currentList.indexOfFirst { it is ArtworkPageItem && it.pageIndex == pageIndex }
        if (pos < 0) return false
        val cell = feedBinding.feedListView.findViewHolderForAdapterPosition(pos) ?: return false
        val rect = Rect()
        val isVisible = cell.itemView.getGlobalVisibleRect(rect)
        return isVisible && rect.height() > 0
    }

    private fun onAiTaskCompleted(pageIndex: Int, isUpscale: Boolean) {
        if (!isArtworkPageInViewport(pageIndex)) {
            val total = currentPageCount()
            val msg = if (isUpscale) {
                if (total > 1) getString(R.string.string_ai_upscale_page_done, pageIndex + 1)
                else getString(R.string.string_ai_upscale_done)
            } else {
                if (total > 1) getString(R.string.string_ai_rembg_page_done, pageIndex + 1)
                else getString(R.string.string_ai_rembg_done)
            }
            Common.showToast(msg)
        }
    }

    private fun onAiPageChanged(pageIndex: Int) {
        if (tabletStage != null) {
            tabletStage?.refreshAiState(pageIndex)
            return
        }
        val (pos, vh) = findArtworkPageViewHolder(pageIndex) ?: return
        val pa = pageAdapter as? CollapsibleIllustAdapter
        if (vh != null && pa != null) {
            pa.refreshOverlayAndWorking(vh, pageIndex)
        } else {
            feedAdapter?.notifyItemChanged(pos, CollapsibleIllustAdapter.PAYLOAD_AI_UPDATE)
        }
    }

    private fun onAiPageProgress(pageIndex: Int, progress: Float, eta: Float, statusText: String?) {
        if (tabletStage != null) {
            tabletStage?.updateAiProgress(pageIndex, progress, eta, statusText)
            return
        }
        val (_, vh) = findArtworkPageViewHolder(pageIndex) ?: return
        val pa = pageAdapter as? CollapsibleIllustAdapter
        if (vh != null && pa != null) {
            pa.updateWorkingOverlayProgress(vh, pageIndex, progress, eta, statusText)
        }
    }

    private fun refreshAllAiPages() {
        if (tabletStage != null) {
            val count = currentPageCount()
            for (i in 0 until count) {
                tabletStage?.refreshAiState(i)
            }
            return
        }
        val fa = feedAdapter ?: return
        val pa = pageAdapter as? CollapsibleIllustAdapter
        fa.currentList.forEachIndexed { pos, item ->
            if (item is ArtworkPageItem) {
                val cell = feedBinding.feedListView.findViewHolderForAdapterPosition(pos)
                val binding = (cell as? FeedCell<*, *>)?.binding as? RecyIllustDetailBinding
                    ?: (cell as? ViewHolder<*>)?.baseBind as? RecyIllustDetailBinding
                val vh = if (binding != null) ViewHolder(binding) else null
                if (vh != null && pa != null) {
                    pa.refreshOverlayAndWorking(vh, item.pageIndex)
                } else {
                    fa.notifyItemChanged(pos, CollapsibleIllustAdapter.PAYLOAD_AI_UPDATE)
                }
            }
        }
    }

    companion object {
        private const val FAB_ANIMATION_DURATION_MS = 200L

        @JvmStatic
        fun newInstance(illustId: Long): ArtworkV3Fragment {
            return ArtworkV3Fragment().apply {
                arguments = Bundle().apply { putLong("illust_id", illustId) }
            }
        }

        @JvmStatic
        @JvmOverloads
        fun newInstanceSnapshot(snapshotId: String, isAuto: Boolean = false): ArtworkV3Fragment {
            return ArtworkV3Fragment().apply {
                arguments =
                    Bundle().apply {
                        putString(SnapshotManagerFragment.ARG_SNAPSHOT_ID, snapshotId)
                        putBoolean(SnapshotManagerFragment.ARG_SNAPSHOT_IS_AUTO, isAuto)
                    }
            }
        }
    }
}
