package ceui.pixiv.ui.novel

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.CellNovelSeriesHeroBinding
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.api.model.Illust
import ceui.loxia.Novel
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.api.Client
import ceui.pixiv.cache.SeriesCache
import ceui.pixiv.chat.base.viewModels as directViewModel
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedSkeletonView
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.ui.bulk.FetchProgressDialog
import ceui.pixiv.ui.common.IllustCardActionReceiver
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.ui.common.NovelFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.NovelMultiSelectReceiver
import ceui.pixiv.ui.common.openIllustsInViewer
import ceui.pixiv.ui.common.openNovelDetail
import ceui.pixiv.ui.common.openUserActivity
import ceui.pixiv.ui.common.toggleIllustBookmark
import ceui.pixiv.ui.common.toggleNovelBookmark
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.novel.reader.ReaderProgressStore
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.ui.ExportSheet
import ceui.pixiv.ui.task.MergeDownloadNovelSeriesTask
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.widgets.ProgressIndicator
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.hairlinePx
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.shape
import ceui.pixiv.witstudio.theme.v3Font
import com.hjq.toast.Toaster
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** 系列下载的待确认动作，跨旋转保留在 Fragment ViewModel 中。 */
class NovelSeriesDownloadRequestViewModel : ViewModel() {
    var pendingAction: PendingAction? = null

    enum class PendingAction {
        MERGE,
        BATCH_SELECTED,
        DOWNLOAD_ALL,
    }
}

/**
 * 小说系列页（feeds 框架版，V3「小说系列」，见 docs/v3-design-philosophy.md）。
 * 页头是 Hero 卡（叠放封面 + 标题 / 作者 / 连载状态 + 篇数 · 字数 · 阅读时长）、操作行
 * （继续阅读 / 从第 1 篇开始 + 追更 + 下载）与「最新一篇」行；其下是用细线分开的区块：简介 →
 * 作者 → 信息 → 目录（全 app 通用的小说卡 recy_novel，分页追加；不显示「系列」行）。
 * 数据住在 [feedViewModel]（[NovelSeriesFeedSource]）；多选态住在 [selectionModel]
 * （跨配置存活），变化时经 [notifyNovelCardSelectionChanged] 只重绑小说卡的勾选框。
 * 下载入口在操作行；多选时底部浮出「全选 / 下载 (N)」操作条，返回键退出多选。
 */
class NovelSeriesFragment :
    NovelFeedFragment(R.layout.fragment_v3_feed_bottombar),
    NovelMultiSelectReceiver,
    NovelSeriesHeaderActionReceiver,
    NovelSeriesHeroActionReceiver,
    // 简介里的站内作品链接（renderNovelCaption）按 receiver 分发，宿主不接就是一点没反应
    NovelActionReceiver,
    IllustCardActionReceiver,
    UserActionReceiver {

    private val seriesId: Long by lazy { arguments?.getLong(ARG_SERIES_ID, 0L) ?: 0L }

    override val feedViewModel by feedViewModels {
        val id = seriesId
        NovelSeriesFeedSource(id)
    }

    private val selectionModel by viewModels<NovelSeriesSelectionViewModel>()

    private val novelDownloadRequest by directViewModel { NovelSeriesDownloadRequestViewModel() }

    /** 简介折叠态（展开与否归 Fragment，滚走再滚回不重置）。 */
    private val captionCollapse = NovelCaptionCollapse()

    /** 主按钮去哪一篇（null = 没读过），onResume / 追页时重算。 */
    private val readTarget = MutableLiveData<SeriesReadTarget?>(null)

    /** 上次算 [readTarget] 时已知的章节数：uiState 每次发射都会回调，章节数没变就不重算。 */
    private var readTargetChapterCount = -1

    /** 状态栏高度：列表顶不留 padding，页头自己把内容往下让这么多。 */
    private var statusBarInset = 0
    private var navigationBarInset = 0

    private var multiSelectBar: View? = null
    private var multiSelectDownloadBtn: TextView? = null
    private var multiSelectSelectAllBtn: TextView? = null

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> = listOf(
        novelSeriesHeroRenderer(viewLifecycleOwner, novelGlide, readTarget) { statusBarInset },
        novelSeriesCaptionRenderer(captionCollapse, ::scrollCaptionBackIntoView),
        novelAuthorRenderer(viewLifecycleOwner, novelGlide, V3Palette.from(requireContext())),
        novelSeriesInfoRenderer(),
        novelSeriesTocHeaderRenderer(),
        // 整页同一个系列：卡上的「系列」行只会点回本页，不显示。标签照常（受「显示标签」设置约束）
        novelCardRenderer(showSeries = false, multiSelect = this),
    )

    override fun onListReady(listView: RecyclerView) {
        listView.clipToPadding = false
        listView.addItemDecoration(
            NovelSeriesDecoration(requireContext(), V3Palette.from(requireContext()).cardHairline)
        )
    }

    /** 首屏是 Hero 卡与区块，不是小说卡列表：不用骨架，维持转圈圈（同小说详情）。 */
    override fun onCreateSkeletonView(layoutManager: RecyclerView.LayoutManager): FeedSkeletonView? =
        null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val listView = feedBinding.feedListView
        val bottomBar = view.findViewById<FrameLayout>(R.id.bottom_bar)

        addMultiSelectActionBar(bottomBar)

        // 宽度变了（分屏 / 自由窗口改宽度不一定重建）要按新宽度重算宽屏留白；首次布局（旧宽 0）不算
        listView.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            val oldWidth = oldRight - oldLeft
            if (oldWidth != 0 && right - left != oldWidth) (v as RecyclerView).invalidateItemDecorations()
        }

        // 页头没有铺进状态栏的背景，内容滚上去时会从状态栏底下穿过：盖一条不透明的页面底色
        val root = view as ViewGroup
        val scrim = View(ctx).apply {
            setBackgroundColor(ctx.color(R.color.v3_bg))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(scrim, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.TOP))

        // Edge-to-edge：列表顶不留 padding（页头自己让状态栏），底部让出导航栏（多选时再让出操作条）；
        // 下拉刷新的圈从状态栏之下出来。
        var appliedRefreshTop = -1
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            navigationBarInset = bars.bottom
            applyListBottomPadding()
            multiSelectBar?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + ctx.dp(12)
            }
            scrim.updateLayoutParams { height = bars.top }
            if (bars.top != statusBarInset) {
                statusBarInset = bars.top
                heroBinding()?.applyStatusBarInset(bars.top)
            }
            // 挡住「没变就别重设」：setProgressViewOffset 内部会 requestLayout（同小说详情）
            if (bars.top != appliedRefreshTop) {
                appliedRefreshTop = bars.top
                feedBinding.feedRefreshLayout.setProgressViewOffset(false, bars.top, bars.top + ctx.dp(64))
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)

        // 多选时返回键先退出多选；不在多选时不拦（预测式返回只在真要拦时 enabled）
        val exitMultiSelectOnBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = selectionModel.setMultiSelectMode(false)
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, exitMultiSelectOnBack)

        selectionModel.isMultiSelect.observe(viewLifecycleOwner) { enabled ->
            exitMultiSelectOnBack.isEnabled = enabled
            multiSelectBar?.isVisible = enabled
            applyListBottomPadding()
            notifyNovelCardSelectionChanged()
        }
        selectionModel.selectedIds.observe(viewLifecycleOwner) { selected ->
            multiSelectDownloadBtn?.text = getString(R.string.download_selected_count, selected.size)
            val allIds = allNovelIds()
            val allSelected = allIds.isNotEmpty() && selected.containsAll(allIds)
            multiSelectSelectAllBtn?.text = getString(if (allSelected) R.string.deselect_all else R.string.select_all)
            notifyNovelCardSelectionChanged()
        }
        // 追页后章节数变了：重算主按钮该去哪一篇（追进来的新卡绑定时自己读当前勾选态）
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collect {
                    val count = loadedCards().size
                    if (count != readTargetChapterCount) {
                        readTargetChapterCount = count
                        refreshReadTarget()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从阅读器返回时进度已经变了：主按钮的「继续阅读 · 第 N 篇」要跟上
        refreshReadTarget()
    }

    override fun onDestroyView() {
        multiSelectBar = null
        multiSelectDownloadBtn = null
        multiSelectSelectAllBtn = null
        super.onDestroyView()
    }

    private fun applyListBottomPadding() {
        if (view == null) return
        val ctx = requireContext()
        val multi = selectionModel.isMultiSelect.value == true
        // 多选时让出操作条（48dp + 上下 12dp），平时只留 24dp 收尾
        feedBinding.feedListView.updatePadding(bottom = navigationBarInset + ctx.dp(if (multi) 96 else 24))
    }

    /**
     * 屏幕上的页头（滚走被回收了就是 null）。按已挂上的子 View 找，理由同小说详情的 headerBinding：
     * adapter 有待处理的更新时 findViewHolderForAdapterPosition 会短暂返回 null。
     */
    private fun heroBinding(): CellNovelSeriesHeroBinding? {
        val list = view?.let { feedBinding.feedListView } ?: return null
        for (i in 0 until list.childCount) {
            val cell = list.getChildViewHolder(list.getChildAt(i)) as? FeedCell<*, *> ?: continue
            (cell.binding as? CellNovelSeriesHeroBinding)?.let { return it }
        }
        return null
    }

    /** 收起超长简介后把简介块拉回视口（理由见插画侧 scrollDescBackIntoView 的 KDoc）。 */
    private fun scrollCaptionBackIntoView(itemView: View) {
        if (itemView.top >= 0) return
        val rv = feedBinding.feedListView
        val pos = rv.getChildAdapterPosition(itemView)
        if (pos == RecyclerView.NO_POSITION) return
        rv.scrollToPosition(pos)
    }

    /**
     * 进多选时把目录滑到状态栏下面：入口在页头，目录上面还隔着简介 / 作者 / 信息三个区块，
     * 不滑过去用户只看见操作条、看不见能勾的行。目录头已在屏幕上半截，或已经滚进目录里了就不动。
     */
    private fun scrollTocIntoView() {
        val rv = feedBinding.feedListView
        val lm = rv.layoutManager ?: return
        val pos = feedViewModel.uiState.value.items.indexOfFirst { it is NovelSeriesTocHeaderFeedItem }
        if (pos < 0) return
        val header = lm.findViewByPosition(pos)
        if (header != null && header.top < rv.height / 2) return
        val firstVisible = rv.getChildAt(0)?.let(rv::getChildAdapterPosition) ?: RecyclerView.NO_POSITION
        if (header == null && firstVisible > pos) return
        val scroller = object : LinearSmoothScroller(rv.context) {
            override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int) =
                boxStart + statusBarInset - viewStart
        }
        scroller.targetPosition = pos
        lm.startSmoothScroll(scroller)
    }

    /**
     * 主按钮去哪一篇：章节表优先取 [SeriesCache]（阅读器翻页 / 选话时已经把整条系列拉全了），
     * 没有就用本页已加载的目录行。规则见 [seriesReadTarget]。
     */
    private fun refreshReadTarget() {
        val cards = loadedCards()
        val cached = SeriesCache.peekNovelSeries(seriesId)?.items
        val chapters = if (cached != null && cached.size > cards.size) {
            cached.mapIndexed { i, novel ->
                SeriesChapterProgress(
                    novel.id, i + 1, novelSeriesReadPercent(novel), ReaderProgressStore.loadLastReadTime(novel.id),
                )
            }
        } else {
            cards.mapIndexed { i, card ->
                SeriesChapterProgress(
                    card.novel.id, i + 1, novelSeriesReadPercent(card.novel),
                    ReaderProgressStore.loadLastReadTime(card.novel.id),
                )
            }
        }
        val target = seriesReadTarget(chapters, ReaderProgressStore.loadSeriesLastNovel(seriesId))
        if (readTarget.value != target) readTarget.value = target
    }

    // ── 已加载的章节（目录顺序 = 系列顺序）──────────────────────────────
    private fun loadedCards(): List<NovelFeedItem> =
        feedViewModel.uiState.value.items.filterIsInstance<NovelFeedItem>()

    private fun loadedNovels(): List<Novel> = loadedCards().map { it.novel }

    private fun allNovelIds(): List<Long> = loadedNovels().map { it.id }

    // ── 多选操作条 ──────────────────────────────────────────────────────
    /** 「全选 / 下载 (N)」：悬浮在目录上，两颗 48dp 胶囊，主次分明（下载实色、全选不透明浅底）。 */
    private fun addMultiSelectActionBar(bottomBar: FrameLayout) {
        val ctx = requireContext()
        val palette = V3Palette.from(ctx)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(48)).apply {
                setMargins(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            }
            visibility = View.GONE
        }
        fun pillText(@ColorInt color: Int) = TextView(ctx).apply {
            setTextColor(color)
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = ctx.v3Font(600)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
            pressScale()
        }
        val selectAll = pillText(palette.textAccent).apply {
            text = getString(R.string.select_all)
            // 用不透明 cardFill 代替半透明 pillSecondary：操作条悬浮在目录上，半透明底会透出下面的字
            background = ctx.ripple(
                palette.settingsCardBg(999f, ctx.hairlinePx()),
                shape(999f, Color.WHITE),
            )
            elevation = ctx.dpF(2f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginEnd = ctx.dp(10) }
            setOnClick { onClickSelectAllToggle() }
        }
        val download = pillText(palette.onPrimary).apply {
            text = getString(R.string.download_selected_count, 0)
            background = ctx.ripple(palette.pillPrimary(999f), shape(999f, Color.WHITE))
            elevation = ctx.dpF(4f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f)
            setOnClick { launchBatchDownloadSelected() }
        }
        row.addView(selectAll)
        row.addView(download)
        bottomBar.addView(row)
        multiSelectBar = row
        multiSelectSelectAllBtn = selectAll
        multiSelectDownloadBtn = download
    }

    private fun onClickSelectAllToggle() {
        val selected = selectionModel.selectedIds.value.orEmpty()
        val allIds = allNovelIds()
        if (allIds.isEmpty()) return
        if (selected.containsAll(allIds)) selectionModel.clearSelection()
        else selectionModel.selectAll(allIds)
    }

    private fun showDownloadOptionsSheet() {
        if (!isAdded) return
        SeriesDownloadOptionsSheet().apply {
            configure { action ->
                when (action) {
                    SeriesDownloadOptionsSheet.Action.Picker -> {
                        selectionModel.setMultiSelectMode(true)
                        scrollTocIntoView()
                    }
                    SeriesDownloadOptionsSheet.Action.AllSeparate -> launchDownloadAll()
                    SeriesDownloadOptionsSheet.Action.MergeOne -> launchMergeDownload()
                }
            }
        }.show(childFragmentManager, SeriesDownloadOptionsSheet.TAG)
    }

    private fun heroItem() = feedViewModel.uiState.value.items
        .filterIsInstance<NovelSeriesHeroFeedItem>().firstOrNull()

    private fun heroDetail() = heroItem()?.series

    /**
     * 合并下载：有默认格式直接走快路径；默认“每次询问”时弹 [ExportSheet]。
     * 默认 TXT 且开启「有插图时存 EPUB」时，抓完章节后由 [startMergeDownload] 弹 TXT/EPUB 质询。
     */
    private fun launchMergeDownload() {
        if (heroDetail() == null) {
            // 点按的前置条件提示，不过「下载相关提示消息」闸门：被安静后这一下点击会毫无回音。
            Toaster.show(getString(R.string.merge_download_failed_empty))
            return
        }
        val format = NovelExportManager.resolveConfiguredFormat()
        if (format != null) {
            startMergeDownload(format, allowAutoEpub = true)
        } else {
            novelDownloadRequest.pendingAction = NovelSeriesDownloadRequestViewModel.PendingAction.MERGE
            ExportSheet().show(childFragmentManager, ExportSheet.TAG)
        }
    }

    override fun onExportFormatChosen(format: ExportFormat) {
        val action = novelDownloadRequest.pendingAction
        novelDownloadRequest.pendingAction = null
        when (action) {
            NovelSeriesDownloadRequestViewModel.PendingAction.BATCH_SELECTED -> {
                val novels = selectedNovels()
                val ordered = loadedNovels().distinctBy { it.id }
                if (novels.isNotEmpty()) {
                    startBatchDownloadSelected(novels, ordered, format)
                }
            }
            NovelSeriesDownloadRequestViewModel.PendingAction.DOWNLOAD_ALL -> {
                launchDownloadAll(format)
            }
            NovelSeriesDownloadRequestViewModel.PendingAction.MERGE -> {
                startMergeDownload(format)
            }
            null -> Unit
        }
    }

    private fun startMergeDownload(format: ExportFormat, allowAutoEpub: Boolean = false) {
        val detail = heroDetail()
        if (detail == null) {
            Toaster.show(getString(R.string.merge_download_failed_empty))
            return
        }
        val dedup = loadedNovels().distinctBy { it.id }
        val stopSignal = AtomicBoolean(false)
        val flow = MergeDownloadNovelSeriesTask(requireContext()).bulkMergeNovelSeries(
            seriesDetail = detail,
            knownNovels = dedup,
            format = format,
            stopSignal = stopSignal,
            allowAutoEpub = allowAutoEpub,
            confirmFormat = if (allowAutoEpub) { _ -> askMergeTxtOrEpub() } else null,
        )
        val config = FetchProgressDialog.Config(
            title = "merge-novel-series",
            headerCmd = "\$ merge-novel-series --format=${format.extension} --stream --verbose",
            showOpenManager = false,
            itemNoun = "chapters",
            stepNoun = "ch",
            completedVerb = "merged",
            canceledVerb = "kept",
            closeHintRes = R.string.merge_novel_dialog_close_hint,
            canceledLineRes = R.string.merge_novel_dialog_canceled,
            stopRequestedLineRes = R.string.merge_novel_dialog_stop_requested,
            doneTitleRes = R.string.merge_novel_dialog_done_title,
            doneTotalRes = R.string.merge_novel_dialog_done_total,
            donePagesRes = R.string.merge_novel_dialog_done_pages,
            doneExtraRes = emptyList(),
            failedTitleRes = R.string.merge_novel_dialog_failed_title,
            failedMessageRes = R.string.merge_novel_dialog_failed_message,
            failedPartialRes = R.string.merge_novel_dialog_failed_partial,
            cancelMode = FetchProgressDialog.CancelMode.COOPERATIVE,
            keepOpenUntilDone = true,
            onCancelRequested = { stopSignal.set(true) },
        )
        FetchProgressDialog.show(requireActivity().supportFragmentManager, flow, config)
    }

    private suspend fun askMergeTxtOrEpub(): ExportFormat? = withContext(Dispatchers.Main) {
        var dialog: WitDialog? = null
        try {
            suspendCancellableCoroutine { cont ->
                if (!isAdded) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                val created = WitDialog.MenuDialogBuilder(requireContext())
                    .setTitle(getString(R.string.setting_novel_epub_on_images))
                    .addItems(
                        arrayOf(getString(R.string.format_txt), getString(R.string.format_epub)),
                    ) { d, which ->
                        if (cont.isActive) {
                            cont.resume(if (which == 0) ExportFormat.Txt else ExportFormat.Epub)
                        }
                        d.dismiss()
                    }
                    .addAction(getString(R.string.action_cancel)) { d, _ ->
                        if (cont.isActive) cont.resume(null)
                        d.dismiss()
                    }
                    .create()
                dialog = created
                created.setOnDismissListener {
                    if (cont.isActive) cont.resume(null)
                }
                created.show()
            }
        } finally {
            // Activity 销毁会取消抓取协程；同步关闭依附旧 Activity 的窗口。
            dialog?.dismiss()
        }
    }

    private fun launchBatchDownloadSelected() {
        val novels = selectedNovels()
        if (novels.isEmpty()) {
            // 同上：「下载 (0)」按钮的回音，不是批量下载的结果，不受闸门控制。
            Toaster.show(getString(R.string.batch_download_no_selection))
            return
        }
        // 系列位置按已加载的完整章节序列算，不能按选中子集的下标算——
        // 勾选第 3、5、9 章时，文件名 / 信息头里要的是 3、5、9 而不是 1、2、3。
        val ordered = loadedNovels().distinctBy { it.id }
        val format = NovelExportManager.resolveConfiguredFormat()
        if (format != null) {
            startBatchDownloadSelected(novels, ordered, format)
        } else {
            novelDownloadRequest.pendingAction = NovelSeriesDownloadRequestViewModel.PendingAction.BATCH_SELECTED
            ExportSheet().show(childFragmentManager, ExportSheet.TAG)
        }
    }

    private fun startBatchDownloadSelected(novels: List<Novel>, ordered: List<Novel>, format: ExportFormat) {
        PixivActions.downloadNovels(
            novels = novels,
            format = format,
            seriesPositions = seriesPositionsOf(ordered),
            seriesTotal = seriesTotalCount(loadedCount = ordered.size),
        )
        // 已经交给进程级下载队列，结果由队列提示；多选态留着没有意义。
        selectionModel.setMultiSelectMode(false)
    }

    /** novelId → 1-based 系列位置。[ordered] 必须是从系列第 1 篇起的有序列表。 */
    private fun seriesPositionsOf(ordered: List<Novel>): Map<Long, Int> =
        ordered.withIndex().associate { (i, n) -> n.id to i + 1 }

    /**
     * 系列总篇数优先取系列详情的 content_count；hero 卡尚未加载成功时退回
     * 已加载章节数（此时多半也只下载得到这些）。
     */
    private fun seriesTotalCount(loadedCount: Int): Int =
        heroDetail()?.content_count?.takeIf { it > 0 } ?: loadedCount

    private fun selectedNovels(): List<Novel> {
        val selected = selectionModel.selectedIds.value.orEmpty()
        if (selected.isEmpty()) return emptyList()
        return loadedNovels().filter { it.id in selected }
    }

    private fun launchDownloadAll() {
        val format = NovelExportManager.resolveConfiguredFormat()
        if (format != null) {
            launchDownloadAll(format)
        } else {
            novelDownloadRequest.pendingAction = NovelSeriesDownloadRequestViewModel.PendingAction.DOWNLOAD_ALL
            ExportSheet().show(childFragmentManager, ExportSheet.TAG)
        }
    }

    private fun launchDownloadAll(format: ExportFormat) {
        PixivActions.downloadNovelSeries(seriesId, format)
    }

    // ── NovelMultiSelectReceiver ────────────────────────────────────────
    override fun isNovelMultiSelectMode(): Boolean = selectionModel.isMultiSelect.value == true
    override fun isNovelSelected(novelId: Long): Boolean =
        selectionModel.selectedIds.value?.contains(novelId) == true
    override fun onToggleNovelSelection(novelId: Long) = selectionModel.toggleSelection(novelId)

    // ── NovelSeriesHeroActionReceiver ───────────────────────────────────
    override fun onClickSeriesRead(sender: View) {
        val novelId = readTarget.value?.novelId ?: heroItem()?.firstNovelId ?: return
        startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_READER.key)
            putExtra(Params.NOVEL_ID, novelId)
        })
    }

    override fun onClickSeriesDownload(sender: View) = showDownloadOptionsSheet()

    override fun onClickShareSeries(sender: View) {
        val detail = heroDetail() ?: return
        val text = getString(R.string.share_illust, detail.title, detail.user?.name, novelSeriesUrl(detail.id))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    /** 点封面看原尺寸封面（同小说详情：先试母图，退回缩略图）。 */
    override fun onClickSeriesCover(sender: View) {
        val cover = heroItem()?.coverUrl ?: return
        val url = novelCoverMasterUrl(cover) ?: cover
        startActivity(Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(Params.URL, GlideUtil.getUrl(url).toStringUrl())
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.IMAGE_DETAIL.key)
        })
    }

    // ── NovelSeriesHeaderActionReceiver ─────────────────────────────────
    override fun onClickToggleWatchlist(progressView: ceui.pixiv.widgets.ProgressImageButton) {
        val detail = heroDetail() ?: return
        progressView.showProgress()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val nowAdded = detail.watchlist_added == true
                val seriesIdInt = detail.id.toInt()
                if (nowAdded) Client.appApi.postWatchlistNovelDelete(seriesIdInt)
                else Client.appApi.postWatchlistNovelAdd(seriesIdInt)
                feedViewModel.updateItems<NovelSeriesHeroFeedItem> {
                    it.copy(series = it.series.copy(watchlist_added = !nowAdded))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) Toaster.show(getString(R.string.task_status_error))
            } finally {
                if (isAdded) progressView.hideProgress()
            }
        }
    }

    override fun onClickReadLatestEpisode(novelId: Long) = openNovelDetail(novelId)

    // ── 简介里的站内链接（同小说详情）────────────────────────────────────
    override fun onClickNovel(novelId: Long) = openNovelDetail(novelId)

    override fun visitNovelById(novelId: Long) = openNovelDetail(novelId)

    override fun onClickBookmarkNovel(sender: ProgressIndicator, novelId: Long) =
        toggleNovelBookmark(sender, novelId)

    override fun onClickIllustCard(illust: Illust) = openIllustsInViewer(listOf(illust), 0)

    override fun visitIllustById(illustId: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val illust = runCatching { Client.appApi.getIllust(illustId).illust }
                .getOrNull() ?: return@launch
            openIllustsInViewer(listOf(illust), 0)
        }
    }

    override fun onClickBookmarkIllust(sender: ProgressIndicator, illustId: Long) =
        toggleIllustBookmark(sender, illustId)

    override fun onClickUser(id: Long) = openUserActivity(id)

    companion object {
        const val ARG_SERIES_ID = "series_id"

        fun newInstance(seriesId: Long): NovelSeriesFragment = NovelSeriesFragment().apply {
            arguments = Bundle().apply { putLong(ARG_SERIES_ID, seriesId) }
        }
    }
}
