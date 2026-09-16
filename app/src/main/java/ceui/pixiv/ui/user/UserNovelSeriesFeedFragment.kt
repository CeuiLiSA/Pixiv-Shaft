package ceui.pixiv.ui.user

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.BaseActivity
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentToolbarFeedBinding
import ceui.lisa.databinding.RecyNovelSeriesOfUserBinding
import ceui.pixiv.api.Client
import ceui.pixiv.chat.base.viewModels
import ceui.lisa.model.ListNovelSeries
import ceui.lisa.models.NovelSeriesItem
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.lisa.view.LinearItemDecoration
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedFragment
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.feeds.pixiv.PixivFeedSource
import ceui.pixiv.feeds.pixiv.pixivFeedSource
import ceui.pixiv.ui.common.setUpToolbar
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.novel.CrossSeriesDownloadOptionsSheet
import ceui.pixiv.ui.novel.NovelSeriesFragment
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.ui.ExportFormatCallback
import ceui.pixiv.ui.novel.reader.ui.ExportSheet
import ceui.pixiv.ui.task.CrossSeriesDownloadTask
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlin.math.floor
import ceui.pixiv.ui.navigation.TemplateRoute

/** 跨系列下载的待确认请求，跨旋转保留在 Fragment ViewModel 中。 */
class CrossSeriesDownloadRequestViewModel : ViewModel() {
    var pendingSeriesList: List<NovelSeriesItem>? = null
    var mergeAll: Boolean = false
}

/**
 * 某作者「小说系列」总览页（feeds 框架版，替代 legacy [ceui.lisa.fragments.FragmentNovelSeries] +
 * NovelSeriesAdapter）。注意：这是作者的系列**总览列表**，不是单个系列详情页
 * [NovelSeriesFragment]（后者按 series id 展开某一系列的章节）。
 *
 * 宿主是 TemplateActivity，用 feeds 独立页统一的 fragment_toolbar_feed（webview 5 件套 toolbar），
 * toolbar 标题 [R.string.string_257]。卡形 1:1 复刻 legacy NovelSeriesAdapter 的
 * recy_novel_series_of_user（标题 / 简介 / 作品数·字数·预计时长），整卡点击进单个系列详情。
 *
 * 顶部 toolbar 保留 legacy 的跨系列批量下载入口（menu [R.menu.cross_series_download]）：点击弹出
 * [CrossSeriesDownloadOptionsSheet] 三选一——
 *   - 选择下载：多选系列，每个系列各自合并为独立文件；
 *   - 全部下载：全部系列，每个各自合并为独立文件；
 *   - 合并下载：全部系列合并为唯一一个文件。
 * 选完模式后按「默认小说下载格式」直接执行或弹格式选择（TXT/MD/PDF/EPUB），
 * 回调走 [CrossSeriesDownloadTask]。
 * 该流程只依赖 [allItems]（当前列表快照）/ activity / childFragmentManager / isAdded，与 legacy 等价。
 */
class UserNovelSeriesFeedFragment : FeedFragment(), ExportFormatCallback {

    private val binding by viewBinding(FragmentToolbarFeedBinding::bind)

    private val novelDownloadRequest by viewModels { CrossSeriesDownloadRequestViewModel() }

    /**
     * 两种形态,对齐 [UserNovelFeedFragment]:
     * - 独立(TemplateActivity「小说系列作品」,showToolbar=true,legacy 默认):自带返回箭头 + 标题 +
     *   顶部跨系列批量下载菜单;
     * - 内嵌(UserActivityV3「小说系列」Tab,showToolbar=false):去掉 toolbar,否则 Tab 里会多顶一条头。
     */
    private val showToolbar: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getBoolean(Params.FLAG, true)
    }

    // 内嵌 UserActivityV3 tab(无底栏)时,列表底部补手势条 inset;带 toolbar 独立页由 setUpToolbar 自理
    override val applyBottomSafeInset: Boolean = true

    // autoLoad = false：本页会作为 UserActivityV3 的「小说系列」tab 挂进 ViewPager2，而 pager 会在
    // 用户滑到相邻位置时就把 fragment 建出来。默认的 autoLoad 会在 onViewCreated 首次访问
    // feedViewModel 时（VM 的 init）直接发请求——tab 从没被打开也请求了。同宿主的漫画 / 小说 /
    // 约稿 tab 全是 autoLoad=false，此处对齐；首屏由 FeedFragment.onResume 的 ensureLoaded 补，
    // 独立 TemplateActivity 形态（一进来就 RESUMED）不受影响。
    override val feedViewModel by feedViewModels(autoLoad = false) {
        // 零捕获：只从 arguments / intent 取出 userID(Long)，source 仅持有这个值，不碰 Fragment/View。
        // legacy 从 activity intent 读 USER_ID；newInstance 存进 args，这里 args 优先、缺失回退 intent。
        val userID = Params.getLongCompat(arguments, ARG_USER_ID).takeIf { it != 0L }
            ?: Params.getUserId(requireActivity().intent)
        userNovelSeriesFeedSource(userID)
    }

    // showToolbar 是运行时参数,系统重建只走无参构造,不能靠构造器传 contentLayoutId,
    // 改在这里按参数选骨架(两张布局都带同结构的 feed_root)。对齐 UserNovelFeedFragment。
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val layoutId = if (showToolbar) R.layout.fragment_toolbar_feed else ceui.pixiv.feeds.R.layout.fragment_feed
        return inflater.inflate(layoutId, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 内嵌 tab 无 toolbar:跳过标题 / 返回箭头 / 跨系列下载菜单(它们都挂在 toolbar 上)。
        if (!showToolbar) return
        setUpToolbar(binding, feedBinding.feedListView)
        binding.toolbarTitle.text = getString(R.string.string_257)
        // 顶部下载 icon，复用 legacy 同一套 menu / itemId。
        binding.toolbar.inflateMenu(R.menu.cross_series_download)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.cross_series_download) {
                onClickDownloadEntry()
                true
            } else {
                false
            }
        }
    }

    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(novelSeriesRenderer())
    }

    override fun onListReady(listView: RecyclerView) {
        // 卡间距对齐 legacy ListFragment 的 LinearItemDecoration(12dp)（FragmentNovelSeries 未覆写列表模式）。
        listView.addItemDecoration(LinearItemDecoration(12.ppppx))
    }

    // ── 卡片渲染（对齐 NovelSeriesAdapter.bindData）─────────────────────────

    private fun novelSeriesRenderer() =
        feedRenderer<NovelSeriesFeedItem, RecyNovelSeriesOfUserBinding>(
            inflate = RecyNovelSeriesOfUserBinding::inflate,
            // 整卡点击进单个系列详情，与 legacy adapter 的 itemView 点击一字不差；点击那一刻读 cell.item。
            create = { cell -> cell.binding.root.setOnClick { openSeries(cell.item.series) } },
        ) { cell -> bindRow(cell) }

    private fun bindRow(cell: FeedCell<NovelSeriesFeedItem, RecyNovelSeriesOfUserBinding>) {
        val b = cell.binding
        val series = cell.item.series
        b.title.text = series.title
        b.description.text = series.display_text
        // 预计阅读时长：按 500 字/分钟估算（与 legacy NovelSeriesAdapter 完全一致）。
        val minute: Float = series.total_character_count / 500.0f
        b.extraDescription.text = getString(
            R.string.how_many_novels,
            series.content_count,
            series.total_character_count,
            floor(minute / 60).toInt(),
            (minute % 60).toInt(),
        )
    }

    /** 整卡点击：进单个系列详情（series id 走 [NovelSeriesFragment.ARG_SERIES_ID]，long）。 */
    private fun openSeries(series: NovelSeriesItem) {
        val intent = Intent(requireContext(), TemplateActivity::class.java)
        intent.putExtra(NovelSeriesFragment.ARG_SERIES_ID, series.id.toLong())
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_SERIES.key)
        startActivity(intent)
    }

    // ── 跨系列批量下载（照搬 legacy FragmentNovelSeries，allItems 改读 uiState 快照）──────────

    /** 当前列表快照：uiState 里的系列条目摊平回 legacy 的 [NovelSeriesItem] 列表，供下载流程读取。 */
    private fun allItems(): List<NovelSeriesItem> =
        feedViewModel.uiState.value.items
            .filterIsInstance<NovelSeriesFeedItem>()
            .map { it.series }

    private fun onClickDownloadEntry() {
        if (!isAdded) return
        val items = allItems()
        if (items.isEmpty()) {
            Common.showToast(getString(R.string.cross_series_no_series_loaded))
            return
        }
        val sheet = CrossSeriesDownloadOptionsSheet()
        sheet.configure { option ->
            when (option) {
                CrossSeriesDownloadOptionsSheet.Option.Pick -> showSeriesPicker()
                CrossSeriesDownloadOptionsSheet.Option.All -> runPerSeries(allItems())
                CrossSeriesDownloadOptionsSheet.Option.Merge -> runMergeAll()
            }
        }
        sheet.show(childFragmentManager, CrossSeriesDownloadOptionsSheet.TAG)
    }

    /**
     * 多选对话框：[WitDialog.MultiCheckableDialogBuilder]。避免把 ActionMode 选择态引进卡片，
     * 保持列表页自身不变（与 legacy 同一思路）。
     */
    private fun showSeriesPicker() {
        val ctx = context ?: return
        val list = allItems()
        if (list.isEmpty()) {
            Common.showToast(getString(R.string.cross_series_no_series_loaded))
            return
        }
        val titles: Array<CharSequence> = list.map { it.title.orEmpty() as CharSequence }.toTypedArray()

        val builder = WitDialog.MultiCheckableDialogBuilder(ctx)
            .setTitle(getString(R.string.cross_series_pick_dialog_title))
            .setCheckedItems(intArrayOf())
        builder.addItems(titles) { _, _ -> /* multi-state auto-tracked */ }
        builder.addAction(getString(R.string.cross_series_pick_dialog_cancel)) { d, _ -> d.dismiss() }
        builder.addAction(getString(R.string.sure)) { d, _ ->
            val indexes = builder.checkedItemIndexes
            if (indexes == null || indexes.isEmpty()) {
                Common.showToast(getString(R.string.cross_series_pick_empty))
                return@addAction
            }
            val pickedSet = indexes.toHashSet()
            val picked = list.filterIndexed { idx, _ -> pickedSet.contains(idx) }
            d.dismiss()
            runPerSeries(picked)
        }
        builder.create().show()
    }

    private fun runPerSeries(seriesList: List<NovelSeriesItem>) {
        if (!isAdded) return
        val format = NovelExportManager.resolveConfiguredFormat()
        if (format != null) {
            startPerSeries(seriesList, format)
        } else {
            novelDownloadRequest.pendingSeriesList = seriesList
            novelDownloadRequest.mergeAll = false
            ExportSheet().show(childFragmentManager, ExportSheet.TAG)
        }
    }

    private fun runMergeAll() {
        val list = allItems()
        if (list.isEmpty()) {
            Common.showToast(getString(R.string.cross_series_no_series_loaded))
            return
        }
        if (!isAdded) return
        val format = NovelExportManager.resolveConfiguredFormat()
        if (format != null) {
            startMergeAll(list, format)
        } else {
            novelDownloadRequest.pendingSeriesList = list
            novelDownloadRequest.mergeAll = true
            ExportSheet().show(childFragmentManager, ExportSheet.TAG)
        }
    }

    override fun onExportFormatChosen(format: ExportFormat) {
        val seriesList = novelDownloadRequest.pendingSeriesList
        val mergeAll = novelDownloadRequest.mergeAll
        novelDownloadRequest.pendingSeriesList = null
        novelDownloadRequest.mergeAll = false
        if (mergeAll) {
            startMergeAll(seriesList ?: allItems(), format)
        } else if (seriesList != null) {
            startPerSeries(seriesList, format)
        }
    }

    private fun startPerSeries(seriesList: List<NovelSeriesItem>, format: ExportFormat) {
        val act = activity as? BaseActivity<*> ?: return
        CrossSeriesDownloadTask(requireContext()).runPerSeries(
            activity = act,
            seriesList = seriesList,
            format = format,
        ) { _, failures ->
            if (!isAdded) return@runPerSeries
            if (failures.isEmpty()) return@runPerSeries
            val ctx = requireContext()
            val msg = failures.joinToString(separator = "\n") { f ->
                "《${f.seriesTitle}》— ${f.reason}"
            }
            WitDialog.MessageDialogBuilder(ctx)
                .setTitle(
                    getString(R.string.batch_download_some_failed, failures.size)
                )
                .setMessage(msg)
                .addAction(0, android.R.string.ok, WitDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                    d.dismiss()
                }
                .show()
        }
    }

    private fun startMergeAll(list: List<NovelSeriesItem>, format: ExportFormat) {
        val act = activity as? BaseActivity<*> ?: return
        // 作者 id / name：allItems 里任何一项的 user 都指向该作者本人，取第一个非空的兜底 intent。
        val firstUser = list.firstOrNull { it.user != null }?.user
        val authorId = firstUser?.id ?: Params.getUserId(requireActivity().intent)
        val authorName = firstUser?.name
        CrossSeriesDownloadTask(requireContext()).runAllMergedOne(
            activity = act,
            seriesList = list,
            authorName = authorName,
            authorId = authorId,
            format = format,
        ) { _, _ -> /* toast handled inside task */ }
    }

    companion object {
        private const val ARG_USER_ID = "arg_user_id"

        @JvmStatic
        @JvmOverloads
        fun newInstance(userID: Long, showToolbar: Boolean = true): UserNovelSeriesFeedFragment =
            UserNovelSeriesFeedFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_USER_ID, userID)
                    putBoolean(Params.FLAG, showToolbar)
                }
            }
    }
}

/**
 * 系列条目：持 legacy [NovelSeriesItem]（作者系列总览下行的单条）。
 * feedKey 用系列 id（[NovelSeriesItem.getId] 返回 int，同类内唯一稳定）。
 */
class NovelSeriesFeedItem(val series: NovelSeriesItem) : FeedItem {
    override val feedKey: Any get() = series.id
}

/**
 * 作者小说系列总览数据源：首页 getUserNovelSeries，翻页由 [PixivFeedSource] 按 next_url 重放。
 * 零 Fragment 捕获：只捕获一个 userID(Long)。
 */
private fun userNovelSeriesFeedSource(userID: Long): PixivFeedSource<ListNovelSeries> =
    pixivFeedSource(initialFetch = { Client.appApi.getUserNovelSeries(userID) }) { resp, _ ->
        resp.displayList.map { NovelSeriesFeedItem(it) }
    }
