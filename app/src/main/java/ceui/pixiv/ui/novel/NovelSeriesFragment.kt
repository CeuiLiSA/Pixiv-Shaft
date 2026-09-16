package ceui.pixiv.ui.novel

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.databinding.ItemBigReadButtonBinding
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.api.Client
import ceui.pixiv.chat.base.viewModels as directViewModel
import ceui.loxia.Novel
import ceui.pixiv.api.model.NovelSeriesResp
import ceui.pixiv.widgets.ProgressIndicator
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.updateItems
import ceui.pixiv.ui.bulk.FetchProgressDialog
import ceui.pixiv.ui.common.NovelActionReceiver
import ceui.pixiv.ui.common.NovelMultiSelectReceiver
import ceui.pixiv.ui.common.openNovelDetail
import ceui.pixiv.ui.common.openUserActivity
import ceui.pixiv.ui.common.toggleNovelBookmark
import ceui.pixiv.ui.detail.seriesAuthorRenderer
import ceui.pixiv.ui.detail.seriesCaptionRenderer
import ceui.pixiv.ui.detail.seriesSectionLabelRenderer
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.ui.ExportFormatCallback
import ceui.pixiv.ui.novel.reader.ui.ExportSheet
import ceui.pixiv.ui.task.BatchDownloadNovelsTask
import ceui.pixiv.ui.task.FailedNovel
import ceui.pixiv.ui.task.FetchAllTask
import ceui.pixiv.ui.task.HumanReadableTask
import ceui.pixiv.ui.task.MergeDownloadNovelSeriesTask
import ceui.pixiv.ui.task.PixivTaskType
import ceui.pixiv.ui.user.UserActionReceiver
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import com.hjq.toast.Toaster
import ceui.pixiv.witstudio.dialog.WitDialog
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
 * 小说系列 V3 详情页（feeds 框架版）。hero + 作者 + 档案 + 简介 + 「作品列表」标题 + 章节卡。
 * 数据住在 [feedViewModel]（[NovelSeriesFeedSource]）；多选态住在 [selectionModel]
 * （跨配置存活），二者的变化都收敛到 [syncCards] 把章节卡的选中态回灌进 feed。
 * 底部「合集下载」按钮与多选操作条互斥切换。
 */
class NovelSeriesFragment :
    FeedFragment(R.layout.fragment_v3_feed_bottombar),
    NovelMultiSelectReceiver,
    NovelSeriesHeaderActionReceiver,
    NovelActionReceiver,
    UserActionReceiver,
    ExportFormatCallback {

    private val seriesId: Long by lazy { arguments?.getLong(ARG_SERIES_ID, 0L) ?: 0L }

    override val feedViewModel by feedViewModels {
        val id = seriesId
        NovelSeriesFeedSource(id)
    }

    private val selectionModel by viewModels<NovelSeriesSelectionViewModel>()

    private val novelDownloadRequest by directViewModel { NovelSeriesDownloadRequestViewModel() }

    private var singleDownloadBtn: View? = null
    private var multiSelectBar: View? = null
    private var multiSelectDownloadBtn: TextView? = null
    private var multiSelectSelectAllBtn: TextView? = null

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> = listOf(
        novelSeriesHeroRenderer(),
        seriesAuthorRenderer(),
        novelSeriesProfileRenderer(),
        seriesCaptionRenderer(),
        seriesSectionLabelRenderer(),
        novelSeriesCardRenderer(),
    )

    override fun onListReady(listView: RecyclerView) {
        listView.clipToPadding = false
        listView.addItemDecoration(ceui.lisa.view.LinearItemDecorationNoLRTB(18.ppppx))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val density = resources.displayMetrics.density
        val listView = feedBinding.feedListView
        val bottomBar = view.findViewById<FrameLayout>(R.id.bottom_bar)

        addDownloadAllButton(bottomBar)
        addMultiSelectActionBar(bottomBar, density)

        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            listView.updatePadding(top = bars.top + (12 * density).toInt(), bottom = bars.bottom + (96 * density).toInt())
            // 下载 ItemBigReadButton 是 300dp 渐变遮罩容器：铺到屏幕最底(别加 margin 变漂浮)，
            // 只在容器内底 padding 叠加导航栏 inset 抬起按钮；多选操作条是裸条，单独抬。
            singleDownloadBtn?.updatePadding(bottom = (20 * density).toInt() + bars.bottom)
            multiSelectBar?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + (12 * density).toInt()
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)

        selectionModel.isMultiSelect.observe(viewLifecycleOwner) { enabled ->
            applyMultiSelectVisibility(enabled)
            syncCards()
        }
        selectionModel.selectedIds.observe(viewLifecycleOwner) { selected ->
            multiSelectDownloadBtn?.text = getString(R.string.download_selected_count, selected.size)
            val allIds = allNovelIds()
            val allSelected = allIds.isNotEmpty() && selected.containsAll(allIds)
            multiSelectSelectAllBtn?.text = getString(if (allSelected) R.string.deselect_all else R.string.select_all)
            syncCards()
        }
        // 追页后新卡以「非多选」态入列，这里跟随当前多选态回灌（syncCards 自带差异守卫，不会死循环）
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedViewModel.uiState.collect { syncCards() }
            }
        }
    }

    // ── 多选态回灌 feed ─────────────────────────────────────────────────
    private fun loadedNovels(): List<Novel> =
        feedViewModel.uiState.value.items.filterIsInstance<NovelSeriesCardFeedItem>().map { it.novel }

    private fun allNovelIds(): List<Long> = loadedNovels().map { it.id }

    private fun syncCards() {
        val mode = selectionModel.isMultiSelect.value == true
        val selected = selectionModel.selectedIds.value.orEmpty()
        val cards = feedViewModel.uiState.value.items.filterIsInstance<NovelSeriesCardFeedItem>()
        val needsUpdate = cards.any {
            it.isMultiSelectMode != mode || it.isSelected != (it.novel.id in selected)
        }
        if (!needsUpdate) return
        feedViewModel.updateItems<NovelSeriesCardFeedItem> {
            it.copy(isMultiSelectMode = mode, isSelected = it.novel.id in selected)
        }
    }

    // ── 底部按钮 ────────────────────────────────────────────────────────
    private fun addDownloadAllButton(bottomBar: FrameLayout) {
        val palette = V3Palette.from(requireContext())
        val bottomView = ItemBigReadButtonBinding.inflate(layoutInflater)
        bottomView.btnRead.text = getString(R.string.series_download_action)
        bottomView.btnRead.background = palette.pillPrimary(28f * resources.displayMetrics.density)
        bottomView.btnRead.setOnClick { showDownloadOptionsSheet() }
        bottomBar.addView(bottomView.root)
        singleDownloadBtn = bottomView.root
    }

    private fun addMultiSelectActionBar(bottomBar: FrameLayout, density: Float) {
        val palette = V3Palette.from(requireContext())
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            val mx = (20 * density).toInt()
            val my = (12 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt(),
            ).apply { setMargins(mx, my, mx, my) }
            visibility = View.GONE
        }
        val selectAll = TextView(requireContext()).apply {
            text = getString(R.string.select_all)
            setTextColor(palette.textAccent)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            // 用不透明 cardFill 代替半透明 pillSecondary：多选条悬浮在列表上，
            // 半透明底会透出下方章节封面，textAccent 文字难以辨认。
            background = palette.settingsCardBg(28 * density, (1 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginEnd = (10 * density).toInt() }
            setOnClick { onClickSelectAllToggle() }
        }
        val download = TextView(requireContext()).apply {
            text = getString(R.string.download_selected_count, 0)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = palette.pillPrimary(28 * density)
            elevation = 4 * density
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

    private fun applyMultiSelectVisibility(enabled: Boolean) {
        singleDownloadBtn?.isVisible = !enabled
        multiSelectBar?.isVisible = enabled
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
                    SeriesDownloadOptionsSheet.Action.Picker -> selectionModel.setMultiSelectMode(true)
                    SeriesDownloadOptionsSheet.Action.AllSeparate -> launchDownloadAll()
                    SeriesDownloadOptionsSheet.Action.MergeOne -> launchMergeDownload()
                }
            }
        }.show(childFragmentManager, SeriesDownloadOptionsSheet.TAG)
    }

    private fun heroDetail() = feedViewModel.uiState.value.items
        .filterIsInstance<NovelSeriesHeroFeedItem>().firstOrNull()?.series

    /**
     * 合并下载：有默认格式直接走快路径；默认“每次询问”时弹 [ExportSheet]。
     * 默认 TXT 且开启「有插图时存 EPUB」时，抓完章节后由 [startMergeDownload] 弹 TXT/EPUB 质询。
     */
    private fun launchMergeDownload() {
        if (heroDetail() == null) {
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
        BatchDownloadNovelsTask(
            activity = requireActivity(),
            novels = novels,
            format = format,
            onFinished = { failures -> onBatchDownloadFinished(failures) },
            seriesPositions = seriesPositionsOf(ordered),
            seriesTotal = seriesTotalCount(loadedCount = ordered.size),
        )
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

    private fun onBatchDownloadFinished(failures: List<FailedNovel>) {
        if (!isAdded) return
        if (failures.isEmpty()) {
            Toaster.show(getString(R.string.batch_download_all_ok))
            selectionModel.setMultiSelectMode(false)
            return
        }
        val msg = failures.joinToString(separator = "\n") { fn ->
            getString(R.string.batch_download_failure_line, fn.novel.title.orEmpty(), fn.reason.orEmpty())
        }
        WitDialog.MessageDialogBuilder(requireContext())
            .setTitle(getString(R.string.batch_download_some_failed, failures.size))
            .setMessage(msg)
            .addAction(android.R.string.ok) { d, _ -> d.dismiss() }
            .show()
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
        object : FetchAllTask<Novel, NovelSeriesResp>(
            requireActivity(),
            taskFullName = "下载系列小说全部作品-${seriesId}",
            taskType = PixivTaskType.DownloadSeriesNovels,
            initialLoader = { Client.appApi.getNovelSeries(seriesId) },
        ) {
            override fun onEnd(humanReadableTask: HumanReadableTask, results: List<Novel>) {
                if (!isAdded) return
                if (results.isEmpty()) {
                    Toaster.show(getString(R.string.merge_download_failed_empty))
                    return
                }
                // FetchAllTask 拉到的就是整个系列的有序章节，位置 = 下标 + 1。
                val ordered = results.distinctBy { it.id }
                BatchDownloadNovelsTask(
                    activity = requireActivity(),
                    novels = results,
                    format = format,
                    onFinished = { failures -> onBatchDownloadFinished(failures) },
                    seriesPositions = seriesPositionsOf(ordered),
                    seriesTotal = ordered.size,
                )
            }
        }
    }

    // ── NovelMultiSelectReceiver ────────────────────────────────────────
    override fun isNovelMultiSelectMode(): Boolean = selectionModel.isMultiSelect.value == true
    override fun isNovelSelected(novelId: Long): Boolean =
        selectionModel.selectedIds.value?.contains(novelId) == true
    override fun onToggleNovelSelection(novelId: Long) = selectionModel.toggleSelection(novelId)

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

    override fun onClickReadLatestEpisode(novelId: Long) = onClickNovel(novelId)

    // ── NovelActionReceiver（卡片点击 / 收藏）────────────────────────────
    override fun onClickNovel(novelId: Long) = openNovelDetail(novelId)

    override fun visitNovelById(novelId: Long) = onClickNovel(novelId)

    override fun onClickBookmarkNovel(sender: ProgressIndicator, novelId: Long) =
        toggleNovelBookmark(sender, novelId)

    override fun onClickUser(id: Long) = openUserActivity(id)

    companion object {
        const val ARG_SERIES_ID = "series_id"

        fun newInstance(seriesId: Long): NovelSeriesFragment = NovelSeriesFragment().apply {
            arguments = Bundle().apply { putLong(ARG_SERIES_ID, seriesId) }
        }
    }
}
