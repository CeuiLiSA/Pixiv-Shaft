package ceui.pixiv.ui.detail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.bookmark.SelectTagBottomSheet
import ceui.lisa.adapters.IllustAdapter
import ceui.lisa.adapters.ViewHolder
import ceui.lisa.databinding.FragmentArtworkV3Binding
import ceui.lisa.databinding.RecyIllustDetailBinding
import ceui.lisa.dialogs.MuteDialog
import ceui.lisa.download.IllustDownload
import ceui.lisa.helper.StaggeredManager
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.Common
import ceui.lisa.utils.Dev
import ceui.lisa.utils.Params
import ceui.lisa.utils.PixivOperate
import ceui.lisa.utils.ShareIllust
import ceui.lisa.utils.V3Palette
import ceui.lisa.core.Mapper
import ceui.loxia.ObjectPool
import ceui.loxia.ObjectType
import ceui.loxia.requireNetworkStateManager
import ceui.pixiv.chat.base.panel.PanelState
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.ui.comments.CommentComposerController
import ceui.pixiv.ui.comments.CommentComposerPresentation
import ceui.pixiv.ui.comments.CommentTarget
import ceui.pixiv.ui.comments.CommentsComposerViewModel
import ceui.pixiv.ui.comments.SentComment
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.staggerIllustRenderer
import ceui.pixiv.ui.share.shareFirstImage
import ceui.pixiv.ui.task.PageLoadRetryController
import ceui.pixiv.ui.task.renderImageLoadStatusBanner
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.qmuiteam.qmui.widget.dialog.QMUIDialog

/**
 * 插画详情页(feeds 框架版)。整页 = 一张异构瀑布流:顶部大图页 + header 区块(全 fullSpan)+
 * 相关作品瀑布流。列表 / 分页 / 空错态 / DiffUtil 归框架;chrome(toolbar / 悬浮下载收藏胶囊 /
 * 折叠胶囊 / 内联评论输入栏)浮在列表之上,由本 Fragment 直接管理。
 *
 * 顶部大图:每页是一个 [ArtworkPageItem](外层瀑布流回收),bind/recycle **委托**给本页持有的
 * 那一个 [IllustAdapter]/[CollapsibleIllustAdapter] 实例(见 [ensurePageAdapter] /
 * [ArtworkV3Fragment.artworkPageRenderer]),尺寸 / 折叠 / 取图规则与 legacy 逐字一致。
 *
 * 数据源见 [ArtworkV3FeedSource];下载 FAB / 收藏态归 [ArtworkV3ViewModel]。无下拉刷新
 *([refreshEnabled] = false)。
 */
class ArtworkV3Fragment : IllustFeedFragment(R.layout.fragment_artwork_v3) {

    private val illustId: Long by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getInt("illust_id").toLong()
    }

    override val feedViewModel by feedViewModels {
        // 零捕获:只把 id 读进局部值交给长命 VM 持有的数据源,不钉 Fragment。
        val id = requireArguments().getInt("illust_id").toLong()
        ArtworkV3FeedSource(id)
    }

    private val artworkViewModel by viewModels<ArtworkV3ViewModel> {
        viewModelFactory { initializer { ArtworkV3ViewModel(illustId) } }
    }

    /** 底部内联评论输入栏的 composer VM(独立于列表)。 */
    private val composer by viewModels<CommentsComposerViewModel> {
        viewModelFactory {
            initializer { CommentsComposerViewModel(CommentTarget(illustId, ObjectType.ILLUST)) }
        }
    }

    internal val palette: V3Palette by lazy(LazyThreadSafetyMode.NONE) { V3Palette.from(requireContext()) }

    // 顶部大图页共享的那一个 adapter(所有页 bind 都委托给它)。isGif 时不建(走 ugoira renderer)。
    private var pageAdapter: IllustAdapter? = null
    private lateinit var retryController: PageLoadRetryController

    // chrome
    private var _chromeBind: FragmentArtworkV3Binding? = null
    private val chromeBind get() = checkNotNull(_chromeBind) { "view 尚未创建或已销毁" }

    private var commentComposer: CommentComposerController? = null
    private var composerActive = false
    private var fabShown = true

    private var sectionLoader: SectionLoader? = null
    private var artistObservedUserId: Long = 0L

    /** 详情面板展开态归 Fragment(而非 cell tag):滚走再滚回不会被重绑重置(对齐 legacy VH 字段)。 */
    internal var detailPanelExpanded = true

    // 关闭下拉刷新(详情页 feeds 版不支持)
    override val refreshEnabled: Boolean = false

    // ── 列表装配 ────────────────────────────────────────────────────────────

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(
            artworkPageRenderer(),
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

    override fun onCreateLayoutManager(): RecyclerView.LayoutManager {
        // 带整行 header 的瀑布流:GAP_HANDLING_NONE 对齐 legacy(开 gap 策略回滚时重排跳动)
        return StaggeredManager(Shaft.sSettings.lineCount, RecyclerView.VERTICAL).apply {
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        }
    }

    override fun onListReady(listView: RecyclerView) {
        val spanCount = Shaft.sSettings.lineCount.coerceAtLeast(1)
        // 相关作品瀑布流间距对齐外面的推荐插画流(SpacesItemDecoration 也是 8dp);列数跟随设置。
        listView.addItemDecoration(RelatedOnlySpaceDecoration(8.ppppx, spanCount))
        // header 区块(fullSpan)在 notifyItemChanged 时的默认变更动画会打乱 SGLM 的 fullSpan 追踪。
        listView.itemAnimator = null
    }

    // 详情页首屏是大图 + header,不是瀑布流网格——瀑布流骨架图会误导。用居中转圈圈(对齐 legacy)。
    override fun onCreateSkeletonView(
        layoutManager: RecyclerView.LayoutManager,
    ): ceui.pixiv.feeds.FeedSkeletonView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _chromeBind = FragmentArtworkV3Binding.bind(view)
        sectionLoader = SectionLoader(illustId, feedViewModel, viewLifecycleOwner)

        // 旋转 / 视图重建:feedViewModel 的列表存活(可能是展开态),但 pageAdapter 会重建为
        // 折叠态。二者不一致会出「p0 顶着展开胶囊、p1/p2 却已显示」的矛盾 UI。对齐 legacy(旋转即
        // 折叠):在任何页绑定前(此刻 uiState 尚未 render)把多出的页收回,保持与新 adapter 一致。
        ObjectPool.get<IllustsBean>(illustId).value?.let { illust ->
            if (CollapsibleIllustAdapter.shouldCollapse(illust.page_count)) {
                feedViewModel.removeItems { it is ArtworkPageItem && it.pageIndex > 0 }
            }
        }

        retryController = PageLoadRetryController(
            lifecycleOwner = viewLifecycleOwner,
            networkStateManager = requireNetworkStateManager(),
            urlAtIndex = { idx ->
                val illust = ObjectPool.get<IllustsBean>(illustId).value
                    ?: return@PageLoadRetryController null
                if (idx < 0 || idx >= illust.page_count) return@PageLoadRetryController null
                val resolution = if (Shaft.sSettings.isShowOriginalPreviewImage)
                    Params.IMAGE_RESOLUTION_ORIGINAL
                else
                    Params.IMAGE_RESOLUTION_LARGE
                IllustDownload.getUrl(illust, idx, resolution)
            },
            totalPages = { ObjectPool.get<IllustsBean>(illustId).value?.page_count ?: 0 },
            onSummaryChanged = { loaded, total, failed ->
                renderImageLoadStatusBanner(
                    chromeBind.pageStatusRow, chromeBind.pageStatusText, loaded, total, failed,
                )
            },
            onRetryAt = { idx ->
                val fa = feedAdapter ?: return@PageLoadRetryController
                val pos = fa.currentList.indexOfFirst {
                    it is ArtworkPageItem && it.pageIndex == idx
                }
                if (pos >= 0) fa.notifyItemChanged(pos)
            },
        )
        chromeBind.pageStatusRetry.setOnClickListener { retryController.retryAllFailed() }

        setupFabBar()
        setupNavBar()
        handleSystemInsets()
        setupComposer()

        // 隐藏 / 显示悬浮胶囊(滚动)
        feedBinding.feedListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8) hideFabBar() else if (dy < -8) showFabBar()
            }
        })

        // 关注态:观察作者 UserBean,变更后重算 Artist 条目(关注切换只这条重绑)
        ObjectPool.get<IllustsBean>(illustId).observe(viewLifecycleOwner) { illust ->
            val authorId = illust?.user?.id?.toLong() ?: return@observe
            attachArtistFollowObserver(authorId)
        }
    }

    override fun onResume() {
        super.onResume()
        artworkViewModel.refreshDownloadFab()
    }

    override fun onPause() {
        artworkViewModel.pauseDownloadFab()
        super.onPause()
    }

    override fun onDestroyView() {
        commentComposer = null
        composerActive = false
        fabShown = true
        pageAdapter?.release()
        pageAdapter = null
        // 本视图生命周期内的一次性 guard 随视图销毁归零。否则同一 Fragment 实例视图重建(回退栈
        // 重显等)后,旧 viewLifecycleOwner 上的观察已随视图销毁,而 artistObservedUserId 还钉着
        // 旧值 → 关注更新不再触发。区块懒加载的去重集随 sectionLoader 一起丢弃、新视图重建。
        artistObservedUserId = 0L
        sectionLoader = null
        _chromeBind = null
        super.onDestroyView()
    }

    // ── 顶部大图 adapter(委托目标)────────────────────────────────────────────

    /** 首次绑定顶部页时懒建那一个共享 adapter(尺寸 / 折叠 / 取图逻辑全在它里面)。 */
    internal fun ensurePageAdapter(): IllustAdapter? {
        pageAdapter?.let { return it }
        if (view == null || !::retryController.isInitialized) return null
        val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return null
        if (illust.isGif()) return null // ugoira 走自己的 renderer
        val maxHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        val activity = requireActivity()
        val adapter: IllustAdapter = if (CollapsibleIllustAdapter.shouldCollapse(illust.page_count)) {
            val collapsible = CollapsibleIllustAdapter(
                activity, this, illust, maxHeight, false,
                onComicReaderClick = { openComicReader() },
                onExpandedChanged = { expanded -> onPagesExpandedChanged(expanded) },
            )
            // 悬浮「收起」胶囊点击 → 折叠(collapse() 触发 onExpandedChanged(false) → 收回页 + 回顶 + 藏胶囊)
            chromeBind.collapsePill.setOnClickListener { collapsible.collapse() }
            collapsible
        } else {
            object : IllustAdapter(activity, this, illust, maxHeight, false) {
                override fun onBindViewHolder(
                    holder: ViewHolder<RecyIllustDetailBinding>,
                    position: Int,
                ) {
                    super.onBindViewHolder(holder, position)
                    // 多 P 漫画(非折叠,即 2P):点图进漫画阅读器。必须同时判类型——只看
                    // page_count 会把 2P 插画也送进漫画阅读器(#961);3P+ 那条折叠分支的漫画入口
                    // 也是 type == "manga" 才出胶囊(见 CollapsibleIllustAdapter),两边保持一致。
                    // 必须挂在 itemView 而不是 illust 上:super 把「长按下载」挂的是 itemView,
                    // 而一个 clickable 却不 longClickable 的子 View 会把触摸整条吃掉——长按既到不了
                    // itemView 的 longClick,抬手时又照常 performClick,表现成「长按变成打开大图」(#957)。
                    // 同挂 itemView 后长按优先:performLongClick 返回 true 即抑制这次 click。
                    if ("manga" == illust.type && illust.page_count > 1) {
                        holder.itemView.setOnClickListener { openComicReader() }
                    }
                }
            }
        }
        adapter.setPageStatusListener { position, status ->
            retryController.reportStatus(position, status)
        }
        adapter.setLocalPagesChangedListener {
            // IllustAdapter 在 feeds 版只是 bind delegate，并未直接挂到 RecyclerView；它自己的
            // notifyDataSetChanged 无效。下载记录扫描命中后 bump tick，让外层 FeedAdapter 重绑。
            if (_chromeBind != null) {
                feedViewModel.updateItems<ArtworkPageItem> {
                    it.copy(rebindTick = it.rebindTick + 1)
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

    private fun openComicReader() {
        startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "漫画阅读")
            putExtra(Params.ILLUST_ID, illustId.toInt())
        })
    }

    /** 折叠 adapter 的展开态回调:驱动 feed 列表增删剩余页 + 浮动「收起」胶囊。 */
    private fun onPagesExpandedChanged(expanded: Boolean) {
        val pill = chromeBind.collapsePill
        pill.animate().cancel()
        if (expanded) {
            pill.alpha = 0f
            pill.visibility = View.VISIBLE
            pill.animate().alpha(1f).setDuration(220).start()
            val pageCount = ObjectPool.get<IllustsBean>(illustId).value?.page_count ?: return
            feedViewModel.mutateItems { items ->
                val existing = items.filterIsInstance<ArtworkPageItem>().mapTo(HashSet()) { it.pageIndex }
                val toAdd = (1 until pageCount)
                    .filter { it !in existing }
                    .map { ArtworkPageItem(illustId, it) }
                if (toAdd.isEmpty()) return@mutateItems items
                val insertAt = items.indexOfLast { it is ArtworkPageItem } + 1
                items.subList(0, insertAt) + toAdd + items.subList(insertAt, items.size)
            }
        } else {
            pill.animate().alpha(0f).setDuration(220).withEndAction {
                pill.visibility = View.GONE
                pill.alpha = 1f
            }.start()
            // 一次编辑同时:删掉隐藏页 + bump 首页 rebindTick(强制 DiffUtil 原地重绑 p0,
            // 让「展开剩余 X 张」覆盖层重现)。不用 notifyItemChanged/post,避免与在飞的 diff 抢。
            feedViewModel.mutateItems { items ->
                items.mapNotNull { item ->
                    when {
                        item is ArtworkPageItem && item.pageIndex > 0 -> null
                        item is ArtworkPageItem && item.pageIndex == 0 ->
                            item.copy(rebindTick = item.rebindTick + 1)
                        else -> item
                    }
                }
            }
            val lm = feedBinding.feedListView.layoutManager
            if (lm is StaggeredGridLayoutManager) lm.scrollToPositionWithOffset(0, 0)
            else feedBinding.feedListView.scrollToPosition(0)
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

    /**
     * 联网后补拉加载失败的区块。区块的触发信号只有 holder 的 attach，用户如果就停在
     * 那一屏不动（评论/相关区块正在转圈时最常见），不补这一下就再也没有重试时机。
     */
    override fun onNetworkRestored() {
        super.onNetworkRestored()
        sectionLoader?.retryFailed()
    }

    private fun attachArtistFollowObserver(authorId: Long) {
        if (authorId <= 0L || authorId == artistObservedUserId) return
        artistObservedUserId = authorId
        ObjectPool.get<ceui.lisa.models.UserBean>(authorId).observe(viewLifecycleOwner) {
            feedViewModel.updateItems<ArtworkArtistItem> { item ->
                val fresh = ArtworkArtistItem.resolveIsFollowed(item.illust)
                if (item.isFollowed == fresh) item else ArtworkArtistItem(item.illust, fresh)
            }
        }
    }

    // ── 悬浮下载 / 收藏胶囊 ─────────────────────────────────────────────────────

    private fun setupFabBar() {
        val density = resources.displayMetrics.density
        chromeBind.fabBar.background = palette.floatingPillBg(999f * density)
        val pillContent = palette.floatingPillContent
        chromeBind.fabDownload.imageTintList = ColorStateList.valueOf(pillContent)
        chromeBind.fabDivider.setBackgroundColor(V3Palette.withAlpha(pillContent, 0.20f))
        chromeBind.fabDownloadProgress.setIndicatorColor(pillContent)
        chromeBind.fabDownloadProgress.trackColor = V3Palette.withAlpha(pillContent, 0.20f)

        artworkViewModel.isBookmarked.observe(viewLifecycleOwner) { bookmarked ->
            chromeBind.fabBookmark.imageTintList = ColorStateList.valueOf(
                if (bookmarked) requireContext().getColor(R.color.has_bookmarked) else pillContent,
            )
        }
        artworkViewModel.downloadFabState.observe(viewLifecycleOwner) { renderDownloadFab(it) }
        // 网页 ajax 的每页真实宽高到达 → 喂给顶部大图 adapter,预置各页展示 ratio(下载前就摆准高度)。
        // adapter 懒建:值先到就由 ensurePageAdapter 补,adapter 先建就由这里补——两序都覆盖。
        artworkViewModel.pageDimensions.observe(viewLifecycleOwner) { dims ->
            pageAdapter?.seedPageDimensions(dims)
        }
    }

    private fun renderDownloadFab(state: DownloadFab) {
        if (view == null) return
        when (state) {
            DownloadFab.Idle ->
                paintFab(R.drawable.ic_file_download_black_24dp, palette.floatingPillContent)

            DownloadFab.Done ->
                paintFab(R.drawable.ic_file_download_done_24dp, requireContext().getColor(R.color.has_downloaded))

            is DownloadFab.Downloading -> {
                chromeBind.fabDownload.visibility = View.INVISIBLE
                chromeBind.fabDownloadProgress.visibility = View.VISIBLE
                chromeBind.fabDownloadProgress.setProgressCompat(state.percent, true)
            }
        }
    }

    private fun paintFab(@DrawableRes iconRes: Int, @ColorInt tint: Int) {
        chromeBind.fabDownloadProgress.visibility = View.GONE
        chromeBind.fabDownload.visibility = View.VISIBLE
        chromeBind.fabDownload.setImageResource(iconRes)
        chromeBind.fabDownload.imageTintList = ColorStateList.valueOf(tint)
    }

    private fun hideFabBar(immediate: Boolean = false) {
        if (!fabShown && !immediate) return
        fabShown = false
        val fabBar = chromeBind.fabBar
        fabBar.animate().cancel()
        val hiddenTranslation = fabBar.height + 100f
        if (immediate) {
            fabBar.translationY = hiddenTranslation
            fabBar.alpha = 0f
            fabBar.visibility = View.INVISIBLE
        } else {
            fabBar.visibility = View.VISIBLE
            fabBar.animate()
                .translationY(hiddenTranslation)
                .alpha(0f)
                .setDuration(FAB_ANIMATION_DURATION_MS)
                .withEndAction { if (!fabShown) fabBar.visibility = View.INVISIBLE }
                .start()
        }
    }

    private fun showFabBar() {
        if (composerActive) return // 内联输入栏浮着时不放回胶囊
        if (fabShown) return
        fabShown = true
        val fabBar = chromeBind.fabBar
        fabBar.animate().cancel()
        fabBar.visibility = View.VISIBLE
        fabBar.animate().translationY(0f).alpha(1f).setDuration(FAB_ANIMATION_DURATION_MS).start()
    }

    // ── 底部内联评论输入栏 ─────────────────────────────────────────────────────

    private fun setupComposer() {
        commentComposer = CommentComposerController.attach(
            fragment = this,
            view = chromeBind.commentComposer,
            panelRoot = chromeBind.composerRoot,
            panelContentView = feedBinding.feedListView,
            palette = palette,
            presentation = CommentComposerPresentation.ON_DEMAND_OVERLAY,
            composer = composer,
            onSent = ::applySentComment,
            onPanelStateChanged = ::onComposerStateChanged,
            onPanelDismissStarted = { closingState ->
                if (closingState == PanelState.KEYBOARD) {
                    chromeBind.composerRoot.background = null
                }
            },
            onPanelDismissCancelled = ::onComposerStateChanged,
        )
    }

    /** 评论区「留下你的评论吧」入口(由 commentsRenderer 调)。 */
    internal fun showComposer() {
        chromeBind.composerRoot.setBackgroundColor(requireContext().getColor(R.color.v3_bg))
        if (composerActive) {
            commentComposer?.showKeyboard()
            return
        }
        composerActive = true
        hideFabBar(immediate = true)
        commentComposer?.showKeyboard()
    }

    private fun hideComposerBar() {
        composerActive = false
        commentComposer?.hide()
        chromeBind.composerRoot.background = null
        showFabBar()
    }

    private fun onComposerStateChanged(state: PanelState) {
        if (view == null) return // 面板/IME 回调若在视图销毁后到达,别碰 chromeBind(与 renderDownloadFab 对齐)
        if (state == PanelState.NONE) {
            val imeUp = ViewCompat.getRootWindowInsets(chromeBind.composerRoot)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (!imeUp && commentComposer?.isEmpty == true) {
                hideComposerBar()
            } else if (!imeUp) {
                chromeBind.composerRoot.background = null
            }
        } else {
            if (state == PanelState.KEYBOARD) {
                chromeBind.composerRoot.setBackgroundColor(requireContext().getColor(R.color.v3_bg))
            } else {
                chromeBind.composerRoot.background = null
            }
            hideFabBar()
        }
    }

    private fun applySentComment(result: SentComment) {
        val (parentCommentId, comment) = result
        // 内联只发顶层评论;插到预览区最前
        if (parentCommentId <= 0L) {
            feedViewModel.updateItems<ArtworkCommentsItem> { it.prepend(comment) }
        }
        commentComposer?.dismiss()
    }

    // ── inset ──────────────────────────────────────────────────────────────

    private fun handleSystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(chromeBind.toolbar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, insets.top, v.paddingRight, v.paddingBottom)
            windowInsets
        }
        ViewCompat.setOnApplyWindowInsetsListener(chromeBind.fabBar) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin = insets.bottom + 24.ppppx
            v.layoutParams = lp
            windowInsets
        }
        // 列表铺到屏幕最底,底 padding = navBar inset 让末条停在导航栏之上(clipToPadding=false 已设)
        val listView = feedBinding.feedListView
        listView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, insets.bottom)
            windowInsets
        }
        // 折叠「收起」胶囊钉在顶栏(toolbar + 可选重试横幅)之下(见 #881)
        val pillGap = 8.ppppx
        chromeBind.topOverlayColumn.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom == oldBottom || _chromeBind == null) return@addOnLayoutChangeListener
            val pill = chromeBind.collapsePill
            val lp = pill.layoutParams as FrameLayout.LayoutParams
            val target = bottom + pillGap
            if (lp.topMargin != target) {
                lp.topMargin = target
                pill.layoutParams = lp
            }
        }
    }

    // ── toolbar / 悬浮胶囊点击 / more 菜单 ──────────────────────────────────────

    private fun setupNavBar() {
        chromeBind.toolbar.setNavigationOnClickListener { requireActivity().finish() }

        // 下载 / 收藏顺序偏好
        if (!Shaft.sSettings.isArtworkV3FabDownloadOnLeft) {
            val bar = chromeBind.fabBar
            val download = chromeBind.fabDownloadContainer
            val bookmark = chromeBind.fabBookmark
            bar.removeView(download)
            bar.removeView(bookmark)
            bar.addView(bookmark, 0)
            bar.addView(download)
        }

        chromeBind.fabDownloadContainer.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            artworkViewModel.triggerDownload()
            if (Shaft.sSettings.isAutoPostLikeWhenDownload && !illust.isIs_bookmarked) {
                chromeBind.fabBookmark.imageTintList = ColorStateList.valueOf(
                    requireContext().getColor(R.color.has_bookmarked),
                )
                PixivOperate.postLikeDefaultStarType(illust)
            }
        }
        chromeBind.fabDownloadContainer.setOnLongClickListener {
            val illust = ObjectPool.get<IllustsBean>(illustId).value
                ?: return@setOnLongClickListener true
            val baseAct = requireActivity() as? ceui.lisa.activities.BaseActivity<*>
            val resNames = arrayOf(
                getString(R.string.resolution_original),
                getString(R.string.resolution_large),
                getString(R.string.resolution_medium),
                getString(R.string.resolution_square_medium),
            )
            val resValues = arrayOf(
                Params.IMAGE_RESOLUTION_ORIGINAL,
                Params.IMAGE_RESOLUTION_LARGE,
                Params.IMAGE_RESOLUTION_MEDIUM,
                Params.IMAGE_RESOLUTION_SQUARE_MEDIUM,
            )
            QMUIDialog.MenuDialogBuilder(requireContext())
                .addItems(resNames) { dialog, which ->
                    if (illust.page_count == 1) {
                        IllustDownload.downloadIllustFirstPageWithResolution(illust, resValues[which], baseAct)
                    } else {
                        IllustDownload.downloadIllustAllPagesWithResolution(illust, resValues[which], baseAct)
                    }
                    artworkViewModel.refreshDownloadFab()
                    dialog.dismiss()
                }
                .show()
            true
        }

        // Manager 下载完成广播 → 刷新 FAB(轮询期间不干扰)
        val downloadFinishReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // ViewPager 的前后缓存页也注册了 receiver，但它们仅 STARTED；只让当前 RESUMED
                // 页面查下载状态，避免一次完成广播唤醒三页 DB 探测。
                if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    !artworkViewModel.isPollingProgress
                ) {
                    artworkViewModel.refreshDownloadFab()
                }
            }
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            downloadFinishReceiver, IntentFilter(Params.DOWNLOAD_FINISH),
        )
        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                LocalBroadcastManager.getInstance(requireContext())
                    .unregisterReceiver(downloadFinishReceiver)
            }
        })

        chromeBind.fabBookmark.setOnClick {
            val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return@setOnClick
            val willBookmark = !illust.isIs_bookmarked
            // 「未收藏」这一支必须与权威渲染（下面 isBookmarked observer 用的 palette.floatingPillContent）
            // 取同一个色：写死白色的话，取消收藏当帧会闪一帧白，随后才被 observer 纠回胶囊内容色——
            // 浅色主题下那一帧几乎看不见图标。
            chromeBind.fabBookmark.imageTintList = ColorStateList.valueOf(
                if (willBookmark) requireContext().getColor(R.color.has_bookmarked)
                else palette.floatingPillContent,
            )
            PixivOperate.postLikeDefaultStarType(illust)
            if (willBookmark && Shaft.sSettings.isAutoDownloadAfterStar) {
                IllustDownload.downloadIllustAllPages(illust)
            }
        }

        chromeBind.fabBookmark.setOnLongClickListener {
            val illust = ObjectPool.get<IllustsBean>(illustId).value
                ?: return@setOnLongClickListener true
            SelectTagBottomSheet.show(
                this, illust.id, Params.TYPE_ILLUST, illust.tagNames,
            )
            true
        }

        chromeBind.navMore.setOnClick { showMoreMenu() }
    }

    private fun showMoreMenu() {
        val illust = ObjectPool.get<IllustsBean>(illustId).value ?: return
        showV3Menu {
            item(getString(R.string.share), R.drawable.ic_share_black_24dp) {
                object : ShareIllust(requireContext(), illust) {
                    override fun onPrepare() {}
                }.execute()
            }
            item(getString(R.string.string_454), R.drawable.ic_share_black_24dp) {
                shareFirstImage(illust)
            }
            item(getString(R.string.string_355_2), R.drawable.ic_baseline_launch_24) {
                Common.copy(requireContext(), ShareIllust.URL_Head + illust.id)
            }
            item(getString(R.string.string_1), R.drawable.ic_baseline_settings_24) {
                MuteDialog.newInstance(illust).show(childFragmentManager, "MuteDialog")
            }
            item(getString(R.string.string_355), R.drawable.ic_visibility_off_black_24dp) {
                PixivOperate.muteIllust(illust)
            }
            item(getString(R.string.flag_post), R.drawable.ic_baseline_flag_24) {
                val intent = Intent(requireContext(), TemplateActivity::class.java)
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "举报插画")
                intent.putExtra(ceui.loxia.flag.FlagDescFragment.FlagObjectIdKey, illust.id.toLong())
                intent.putExtra(
                    ceui.loxia.flag.FlagDescFragment.FlagObjectTypeKey,
                    ceui.lisa.models.ObjectSpec.POST,
                )
                startActivity(intent)
            }
            item(getString(R.string.string_ai_upscale), R.drawable.ic_upscale_add_photo) {
                ceui.pixiv.ui.upscale.ModelPickerDialog.pickOrUseDefault(childFragmentManager) { }
            }
            if (Dev.showPlazaShareInArtwork) {
                item(getString(R.string.plaza_share_illust_to_plaza), R.drawable.ic_plaza_forum_24) {
                    val intent = Intent(requireContext(), TemplateActivity::class.java)
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "发帖")
                    intent.putExtra(
                        ceui.pixiv.plaza.ui.PlazaComposeFragment.ARG_PREFILL_ILLUST_ID,
                        illust.id.toLong(),
                    )
                    startActivity(intent)
                }
            }
        }
    }

    /**
     * 只给非 fullSpan 条目(相关卡片)加间距;顶部大图页 + header 区块(fullSpan)零 offset。
     * 对齐 legacy 的 RelatedOnlySpaceDecoration。
     */
    private class RelatedOnlySpaceDecoration(
        private val space: Int,
        private val spanCount: Int,
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val lp = view.layoutParams
            if (lp !is StaggeredGridLayoutManager.LayoutParams || lp.isFullSpan) return
            outRect.bottom = space
            val spanIndex = lp.spanIndex
            outRect.left = if (spanIndex == 0) space else space / 2
            outRect.right = if (spanIndex == spanCount - 1) space else space / 2
        }
    }

    companion object {
        private const val FAB_ANIMATION_DURATION_MS = 200L

        @JvmStatic
        fun newInstance(illustId: Int): ArtworkV3Fragment {
            return ArtworkV3Fragment().apply {
                arguments = Bundle().apply { putInt("illust_id", illustId) }
            }
        }

        @JvmStatic
        fun newInstance(illustId: Long): ArtworkV3Fragment = newInstance(illustId.toInt())
    }
}
