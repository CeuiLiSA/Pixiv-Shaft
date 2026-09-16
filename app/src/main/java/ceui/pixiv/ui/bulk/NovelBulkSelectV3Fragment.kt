package ceui.pixiv.ui.bulk

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.Novel
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.chat.base.viewModels
import ceui.pixiv.ui.common.tintMenuIconsWhite
import ceui.pixiv.ui.detail.showV3Menu
import ceui.pixiv.ui.novel.reader.export.ExportFormat
import ceui.pixiv.ui.novel.reader.export.NovelExportManager
import ceui.pixiv.ui.novel.reader.ui.ExportFormatCallback
import ceui.pixiv.ui.novel.reader.ui.ExportSheet
import ceui.pixiv.ui.task.BatchDownloadNovelsTask
import ceui.pixiv.ui.task.FailedNovel
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import com.blankj.utilcode.util.BarUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.hjq.toast.Toaster
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 小说批量下载的待确认选中列表，跨旋转保留在 Fragment ViewModel 中。 */
class NovelBulkDownloadRequestViewModel : ViewModel() {
    var pendingNovels: List<Novel>? = null
}

/** 小说批量选择页的交接数据缓存，跨旋转保留，避免重复 take 导致列表空态。 */
private class NovelBulkSelectRequestViewModel : ViewModel() {
    var source: List<Novel>? = null
    var items: List<SelectableNovel>? = null
    var itemLocaleTags: String? = null
}

/**
 * V3 风格小说批量操作 · 多选页（issue #974）。
 *
 * 入口：小说卡长按 → [ceui.pixiv.ui.common.showNovelCardMenu] →
 *       [NovelBulkSelectHandoff].put → key 随 Intent → TemplateActivity("小说批量选择") → 本页 take。
 *
 * ## 为什么不复用插画那页
 *
 * 两者只有外观像，内里没有一处共用：模型是不可变的 [Novel] 而不是可变的
 * `Illust`；下载走 [BatchDownloadNovelsTask] 直接落盘而不是灌 download_queue，
 * 因此也没有「下载管理」页可跳；没有多页 / 动图徽章、没有「N 张图」这个维度、
 * 没有 original 直链可导出。硬塞进一个 fragment 的结果是每个方法都要先问一句
 * 「这批是什么」，而 SelectableItem 得给小说带一半恒空的字段。
 *
 * ## 内容区是竖列表，不是封面网格
 *
 * pixiv 的小说封面有很大一部分是同一张默认渐变图，3 列铺开根本分不出哪篇是哪篇 ——
 * 小说得靠标题认。所以每行是「小封面 + 标题 + 作者 + 字数/收藏态」，
 * 见 cell_bulk_select_novel_v3。
 *
 * 底栏沿用同一套 MD3-E connected button group：
 *  - 首段（filled）= 下载选中小说。**不 finish 本页**，见 [startDownload]
 *  - 尾段（tonal）= 批量收藏 / 批量取消收藏，走 [PixivActions] 门面 → `:actionqueue` 队列
 */
class NovelBulkSelectV3Fragment : Fragment(), ExportFormatCallback {

    companion object {
        /** [handoffKey] 是入口 put 进 [NovelBulkSelectHandoff] 时拿到的 key；null / 过期时本页显示「没有可选项」。 */
        @JvmStatic
        fun newInstance(handoffKey: String?): NovelBulkSelectV3Fragment = NovelBulkSelectV3Fragment().apply {
            arguments = Bundle().apply { putString(BulkSelectHandoff.ARG_HANDOFF_KEY, handoffKey) }
        }
    }

    private val bulkViewModel by viewModels { NovelBulkSelectRequestViewModel() }

    /** 源列表。勾选结果靠下标换回这里的 [Novel]。 */
    private var source: List<Novel> = emptyList()

    private val items = mutableListOf<SelectableNovel>()

    private val novelDownloadRequest by viewModels { NovelBulkDownloadRequestViewModel() }

    /**
     * 封面的 Glide 请求管理器，建一次复用。**别在 bind 里 `Glide.with(view)`** ——
     * 那条重载每次都递归遍历宿主 fragment 树去找承载该 view 的 fragment，一屏卡 fling
     * 时要跑好几次，全在帧路径上（[ceui.pixiv.ui.common.NovelFeedFragment] 顶上记过同一条）。
     * `Glide.with(Fragment)` 直接命中、无查找，解析出的又是同一个 RequestManager。
     */
    private val coverGlide: RequestManager by lazy { Glide.with(this) }

    private val adapter: NovelBulkSelectAdapter by lazy {
        NovelBulkSelectAdapter(items, coverGlide) { pos -> toggleAt(pos) }
    }

    /** 下载在跑（[BatchDownloadNovelsTask] 是串行的）。挡住 CTA 被再点一次起第二条链。 */
    private var downloadRunning = false

    private lateinit var toolbar: Toolbar
    private lateinit var hint: TextView
    private lateinit var btnConfirm: Button
    private lateinit var btnBookmarkActions: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (bulkViewModel.source == null) {
            bulkViewModel.source = NovelBulkSelectHandoff.take(
                arguments?.getString(BulkSelectHandoff.ARG_HANDOFF_KEY)
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_bulk_select_novel_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { requireActivity().finish() }
        // 单按钮 master-checkbox：icon 跟选中态切，点击行为跟 icon 一致
        //（没全选 → 全选；已全选 → 取消全选），同插画页。
        // 统一 toolbar 的 inset 打法(同 setUpToolbar(FragmentToolbarFeedBinding)):toolbar 自己
        // 顶到状态栏下面,底部导航 inset 作根布局 paddingBottom(原 fitsSystemWindows 的效果)。
        toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        toolbar.inflateMenu(R.menu.menu_bulk_select_novel_v3)
        toolbar.tintMenuIconsWhite()
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_select_toggle) {
                selectAllToggle()
                true
            } else {
                false
            }
        }

        hint = view.findViewById(R.id.hint)
        btnConfirm = view.findViewById(R.id.btnConfirm)
        btnBookmarkActions = view.findViewById(R.id.btnBookmarkActions)
        btnBookmarkActions.setOnClickListener { showBookmarkActionsMenu() }
        btnConfirm.setOnClickListener { startDownload() }

        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        // 只允许首次创建时 take；旋转重建从 ViewModel 恢复，避免重复 take 变成空态。
        val raw = bulkViewModel.source
        if (raw.isNullOrEmpty()) {
            hint.text = getString(R.string.bulk_select_no_items)
            btnConfirm.isEnabled = false
            btnConfirm.text = "—"
            setBookmarkActionsEnabled(false)
            toolbar.menu.findItem(R.id.action_select_toggle)?.isEnabled = false
            return
        }
        source = raw
        hint.text = getString(R.string.bulk_select_loading)
        btnConfirm.isEnabled = false
        setBookmarkActionsEnabled(false)

        // 缓存里的 meta 已本地化；语言变更需要重建文案，但勾选状态仍从旧列表恢复。
        val locales = ConfigurationCompat.getLocales(resources.configuration)
        val localeTags = locales.toLanguageTags()
        val restored = bulkViewModel.items
        if (restored != null && bulkViewModel.itemLocaleTags == localeTags) {
            items.clear()
            items.addAll(restored)
            bulkViewModel.items = items
            adapter.notifyDataSetChanged()
            refreshHeaderAndCta()
        } else {
            // 大列表（上限 20000）的展示数据构造搬 IO，避免主线程长时间循环。
            viewLifecycleOwner.lifecycleScope.launch {
                // 必须用 Activity context 取字符串，**不能图省事换成 applicationContext**：
                // 本 app 有应用内语言设置，Activity 的 Configuration 由
                // BaseActivity.attachBaseContext 保证包成用户选的语言，而 Application 那份在
                // 「切了语言、还没冷启」的窗口里会停留在系统 locale（见 AppLocales
                // .applyConfigurationInPlace 的注释，那个方法存在就是为了补这个洞）。
                // 协程挂在 viewLifecycleOwner 上，view 一销毁就取消，攥不住这个引用。
                val ctx = requireContext()
                // 数字分组也跟着同一份 Configuration 走，别用 Locale.getDefault() ——
                // 那是 JVM 全局值，跟上面读字符串用的 locale 不保证是同一个。
                val locale = locales[0] ?: Locale.getDefault()
                val prepared = withContext(Dispatchers.IO) {
                    val numbers = NumberFormat.getIntegerInstance(locale)
                    raw.mapIndexed { index, novel ->
                        val words = ctx.getString(
                            R.string.v3_novel_word_count, numbers.format(novel.text_length ?: 0)
                        )
                        SelectableNovel(
                            index = index,
                            coverUrl = novel.image_urls?.medium,
                            title = novel.title.orEmpty(),
                            author = novel.user?.name.orEmpty(),
                            // 收藏态直接写在行里：批量收藏 / 取消收藏时，用户得先看得出
                            // 哪些本来就已经收藏了，否则「实际入队 3 条」会显得莫名其妙。
                            meta = if (novel.is_bookmarked == true) {
                                ctx.getString(R.string.bulk_select_novel_meta_bookmarked, words)
                            } else {
                                words
                            },
                            // 默认全不选（同插画页，issue #922）：想要全部的走 toolbar 一键全选。
                            selected = restored?.getOrNull(index)?.selected ?: false,
                        )
                    }
                }
                items.clear()
                items.addAll(prepared)
                bulkViewModel.items = items
                bulkViewModel.itemLocaleTags = localeTags
                adapter.notifyDataSetChanged()
                refreshHeaderAndCta()
            }
        }
    }

    private fun toggleAt(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        items[pos] = items[pos].copy(selected = !items[pos].selected)
        adapter.notifyItemChanged(pos)
        refreshHeaderAndCta()
    }

    private fun selectAllToggle() {
        val target = items.any { !it.selected } // 有未选 → 全选；否则 → 全不选
        viewLifecycleOwner.lifecycleScope.launch {
            val rebuilt = withContext(Dispatchers.IO) { items.map { it.copy(selected = target) } }
            items.clear()
            items.addAll(rebuilt)
            adapter.notifyDataSetChanged()
            refreshHeaderAndCta()
        }
    }

    private fun selectedNovels(): List<Novel> =
        items.asSequence().filter { it.selected }.map { source[it.index] }.toList()

    // ── 首段：下载 ──────────────────────────────────────────────────────────

    /**
     * 批量下载选中的小说。
     *
     * **不能 finish 本页**：[BatchDownloadNovelsTask] 跑在 `activity.lifecycleScope` 上，
     * finish 掉宿主 Activity 等于当场取消下载。所以留在本页，让它自己的「下载中 x/y」
     * toast 当进度条，跑完再报一次结果。这也是小说这一支跟插画页最大的行为差异 ——
     * 插画是灌进 download_queue 后跳「下载管理」看进度，小说不进那张表，没得跳。
     *
     * seriesPositions / seriesTotal 保持 null：这里的小说来自任意列表、不是同一个系列，
     * 标不出「第 X / Y 篇」（[BatchDownloadNovelsTask] 对 null 会自己跳过该字段）。
     */
    private fun startDownload() {
        if (downloadRunning) return
        val picked = selectedNovels()
        if (picked.isEmpty()) return
        val format = NovelExportManager.resolveConfiguredFormat()
        if (format != null) {
            startDownload(picked, format)
        } else {
            novelDownloadRequest.pendingNovels = picked
            ExportSheet().show(childFragmentManager, ExportSheet.TAG)
        }
    }

    override fun onExportFormatChosen(format: ExportFormat) {
        val picked = novelDownloadRequest.pendingNovels ?: return
        novelDownloadRequest.pendingNovels = null
        startDownload(picked, format)
    }

    private fun startDownload(picked: List<Novel>, format: ExportFormat) {
        if (downloadRunning) return
        downloadRunning = true
        btnConfirm.isEnabled = false
        BatchDownloadNovelsTask(
            activity = requireActivity(),
            novels = picked,
            format = format,
            onFinished = { failures -> onDownloadFinished(failures) },
        )
    }

    private fun onDownloadFinished(failures: List<FailedNovel>) {
        downloadRunning = false
        if (!isAdded) return
        Toaster.show(
            if (failures.isEmpty()) {
                getString(R.string.batch_download_all_ok)
            } else {
                getString(R.string.batch_download_some_failed, failures.size)
            }
        )
        refreshHeaderAndCta()
    }

    // ── 尾段：批量收藏 / 取消收藏 ────────────────────────────────────────────

    /**
     * 两项都带**真正会发出去的条数**（已经是目标态的项由
     * [PixivActions.pendingNovelBookmarkCount] 剔掉），而不是勾选数 —— 勾了 200 篇其中
     * 190 篇本来就收藏着的话，「批量收藏 (200 篇)」是句假话。
     */
    private fun showBookmarkActionsMenu() {
        val picked = selectedNovels()
        if (picked.isEmpty()) return
        val toBookmark = PixivActions.pendingNovelBookmarkCount(picked, bookmark = true)
        val toUnbookmark = PixivActions.pendingNovelBookmarkCount(picked, bookmark = false)
        val restrict = PixivActions.defaultBookmarkRestrict()
        val isPrivate = restrict == Params.TYPE_PRIVATE
        val addLabel = getString(
            if (isPrivate) R.string.bulk_bookmark_novel_menu_add_private
            else R.string.bulk_bookmark_novel_menu_add,
            toBookmark,
        )
        showV3Menu("NovelBulkBookmarkMenu") {
            item(addLabel, R.drawable.ic_like_heart_fill) {
                confirmBookmark(picked, toBookmark, restrict, isPrivate)
            }
            item(
                getString(R.string.bulk_bookmark_novel_menu_remove, toUnbookmark),
                R.drawable.ic_like_heart_outline,
            ) {
                confirmUnbookmark(picked, toUnbookmark)
            }
        }
    }

    /**
     * 收藏前的确认框。要讲清用户看不见但影响很大的三件事：收进公开还是私密（跟随
     * 「私密收藏」设置，和单张小说卡同一个判据）、请求是排队逐条发的、全部发完大概多久 ——
     * 队列按最小间隔串行，勾几百篇就是十几分钟，不说清楚会被当成没生效。
     *
     * 不提「收藏后自动关注作者」：小说这一支本来就没有这个副作用（对齐单张小说卡）。
     */
    private fun confirmBookmark(
        picked: List<Novel>,
        count: Int,
        restrict: String,
        isPrivate: Boolean,
    ) {
        if (count == 0) {
            Toaster.showShort(R.string.bulk_bookmark_nothing)
            return
        }
        val ctx = context ?: return
        val restrictLabel = getString(
            if (isPrivate) R.string.bulk_bookmark_restrict_private
            else R.string.bulk_bookmark_restrict_public
        )
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.bulk_bookmark_novel_confirm_title)
            .setMessage(
                getString(
                    R.string.bulk_bookmark_novel_confirm_message,
                    count,
                    restrictLabel,
                    PixivActions.estimatedQueueMinutes(count),
                )
            )
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.bulk_bookmark_confirm_go, WitDialogAction.ACTION_PROP_POSITIVE) { d, _ ->
                d.dismiss()
                enqueueBookmarks(picked, bookmark = true, restrict = restrict)
                finishAfterEnqueue()
            }
            .create()
            .show()
    }

    /** 取消收藏的确认框。这一支是删数据且没有撤销，所以按钮用 NEGATIVE 语义。 */
    private fun confirmUnbookmark(picked: List<Novel>, count: Int) {
        if (count == 0) {
            Toaster.showShort(R.string.bulk_bookmark_nothing)
            return
        }
        val ctx = context ?: return
        WitDialog.MessageDialogBuilder(ctx)
            .setTitle(R.string.bulk_unbookmark_novel_confirm_title)
            .setMessage(
                getString(
                    R.string.bulk_unbookmark_novel_confirm_message,
                    count,
                    PixivActions.estimatedQueueMinutes(count),
                )
            )
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.bulk_bookmark_confirm_go, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                // restrict 对取消收藏无意义（delete 端点不带），传默认值占位。
                enqueueBookmarks(
                    picked, bookmark = false, restrict = PixivActions.defaultBookmarkRestrict(),
                )
                finishAfterEnqueue()
            }
            .create()
            .show()
    }

    /**
     * 收藏入队之后关掉本页 —— 但**下载还在跑的时候不关**。
     *
     * [BatchDownloadNovelsTask] 挂在 `activity.lifecycleScope` 上，finish 掉宿主 Activity 就是
     * 当场取消下载。用户点的是「收藏」、没有要求退出，收藏这个动作不该顺手把他正在跑的下载
     * 弄没了 —— 而且是静默的：任务被取消后 onFinished 根本不会回调，连个提示都没有。
     * 这种时候留在本页，下载自己的进度 toast 继续走。
     *
     * 用 `activity?.` 而不是 `requireActivity()`：WitDialog 拿的是 Activity context、
     * 不跟 fragment 生命周期绑定，点到这里时 fragment 可能已经 detach。
     * 入队本身已经交给进程级 scope，收不收得到这个 finish 都不影响它。
     */
    private fun finishAfterEnqueue() {
        if (downloadRunning) return
        activity?.finish()
    }

    /**
     * 门面负责入队，这里只把它返回的**实际入队条数**报给用户 —— 报返回值而不是确认框上
     * 那个数：两者之间隔着一次用户点击，期间队列可能刚好回滚了某条失败的收藏。
     */
    private fun enqueueBookmarks(picked: List<Novel>, bookmark: Boolean, restrict: String) {
        val enqueued = PixivActions.setNovelBookmarks(picked, bookmark, restrict)
        if (enqueued == 0) {
            Toaster.showShort(R.string.bulk_bookmark_nothing)
            return
        }
        val template = if (bookmark) {
            R.string.bulk_bookmark_novel_enqueued
        } else {
            R.string.bulk_unbookmark_novel_enqueued
        }
        Toaster.showShort(getString(template, enqueued))
    }

    /**
     * 尾段的禁用态。它是个 LinearLayout，[View.setEnabled] 只把自己的 background selector
     * 切到 disabled 那支，里面两个 ImageView 的 tint 不跟着走 —— 另压一层 alpha 让图标同暗。
     */
    private fun setBookmarkActionsEnabled(enabled: Boolean) {
        btnBookmarkActions.isEnabled = enabled
        btnBookmarkActions.alpha = if (enabled) 1f else 0.4f
    }

    private fun refreshHeaderAndCta() {
        val total = items.size
        val selected = items.count { it.selected }
        hint.text = getString(R.string.bulk_select_novel_summary, total, selected)

        btnConfirm.text = if (selected > 0) {
            getString(R.string.bulk_select_novel_confirm, selected)
        } else {
            getString(R.string.bulk_select_confirm_empty)
        }
        // 下载在跑时保持禁用 —— 任务是串行的，再点一次会起第二条并发下载链
        btnConfirm.isEnabled = selected > 0 && !downloadRunning
        setBookmarkActionsEnabled(selected > 0)
        refreshSelectToggleIcon(selected)
    }

    /**
     * Master-checkbox：icon + title 跟「有多少已选中」走，跟 [selectAllToggle] 的行为一致，
     * 用户看到啥 icon 就预期点击会做啥。setIcon 不动 MenuItem 的 iconTintList，
     * 统一白色 tint（tintMenuIconsWhite）不用重设。
     */
    private fun refreshSelectToggleIcon(selectedCount: Int) {
        val item = toolbar.menu.findItem(R.id.action_select_toggle) ?: return
        val allSelected = items.isNotEmpty() && selectedCount == items.size
        item.setIcon(if (allSelected) R.drawable.ic_deselect_24 else R.drawable.ic_select_all_24)
        item.setTitle(if (allSelected) R.string.bulk_select_clear_all else R.string.bulk_select_select_all)
    }
}

/**
 * 一行的**纯展示**数据 —— 不持有 [Novel]，只记它在源列表里的 [index]。
 * 文案（字数、收藏态）在 IO 线程一次算好，bind 时不再碰 getString / NumberFormat。
 */
private data class SelectableNovel(
    val index: Int,
    val coverUrl: String?,
    val title: String,
    val author: String,
    val meta: String,
    val selected: Boolean,
)

private class NovelBulkSelectAdapter(
    private val items: List<SelectableNovel>,
    private val glide: RequestManager,
    private val onToggle: (position: Int) -> Unit,
) : RecyclerView.Adapter<NovelBulkSelectAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.cell_bulk_select_novel_v3, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]

        val showUrl = item.coverUrl
        if (!showUrl.isNullOrEmpty()) {
            glide.load(GlideUtil.getUrl(showUrl))
                .placeholder(android.R.color.transparent).into(h.cover)
        } else {
            glide.clear(h.cover)
            h.cover.setImageDrawable(null)
        }

        h.title.text = item.title
        h.author.text = item.author
        h.meta.text = item.meta

        // 整行 selected 态驱动 bg_novel_bulk_row 的 selector（tonal 底），
        // 右侧勾标同步在「空心圈」和「实心圆 + 白勾」之间切。
        h.itemView.isSelected = item.selected
        if (item.selected) {
            h.checkBadge.setBackgroundResource(R.drawable.bulk_select_check_bg)
            h.checkBadge.setImageResource(R.drawable.ic_check_24dp)
        } else {
            h.checkBadge.setBackgroundResource(R.drawable.bg_novel_bulk_check_idle)
            h.checkBadge.setImageDrawable(null)
        }

        h.itemView.setOnClickListener { onToggle(h.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.cover)
        val title: TextView = v.findViewById(R.id.title)
        val author: TextView = v.findViewById(R.id.author)
        val meta: TextView = v.findViewById(R.id.meta)
        val checkBadge: ImageView = v.findViewById(R.id.checkBadge)
    }
}
