package ceui.pixiv.ui.download

import android.content.Context
import android.content.Intent
import kotlin.math.ceil
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.MessageQueue
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.activities.UActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.ManagerReactive
import ceui.lisa.core.PageData
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadDao
import ceui.lisa.database.DownloadEntity
import ceui.pixiv.api.model.Illust
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Local
import ceui.lisa.utils.Params
import ceui.loxia.Novel
import ceui.pixiv.db.queue.QueueStatus
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.hjq.toast.Toaster
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ceui.pixiv.ui.bulk.QueueDownloadManager
import ceui.pixiv.ui.navigation.TemplateRoute
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * V3 风格 "已完成" — 双列卡片网格。
 *
 * 关键 UX 修复（之前一个 illust 多页会显示 N 个相同卡片）：
 *   Manager 完成每个 page 都插入一条 DownloadEntity（PK=fileName）。
 *   3p 漫画 → 3 条记录 → 原本 3 张卡。
 *   现在先在数据库按作品聚合，再分页加载；角标表示该作品的完整下载记录数。
 *   保留最新 entity（按 downloadTime 取最大）作为代表，点击时查询完整页路径。
 *
 * 数据源：监听 [ManagerReactive.doneTableInvalidations]，按作品查询数据库。
 */
@AndroidEntryPoint
class DoneListV3Fragment : Fragment() {

    /**
     * 注入一次而不是每次 `requireContext()` 去取:清空/重试这类操作跑在 IO 协程里,
     * 阻塞的 DB 调用返回时 Fragment 可能已 detach,再 requireContext 就崩。
     * 实例是应用级的,拿住没有泄漏问题。
     */
    @Inject
    lateinit var queueDownloadManager: QueueDownloadManager

    private val dao: DownloadDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
    }
    private val queueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }
    private val sharedVm: DownloadManagerSharedViewModel by activityViewModels()
    private val loadedGroups = ArrayList<DownloadGroup>()
    private var nextPageOffset = 0
    private var hasMorePages = false
    private var loadingNextPage = false
    private var pageGeneration = 0
    private var doneScrollListener: RecyclerView.OnScrollListener? = null
    private val adapter = DoneAdapterV3(
        initialMode = DoneLayoutMode.fromInt(Shaft.sSettings.doneListLayoutMode),
    ) { group, action ->
        when (action) {
            DoneAction.OPEN -> openDetail(group)
            // 垃圾桶紧贴卡片点击区，缩图布局下尤其容易误触（#1287），单条删除同样先确认
            DoneAction.DELETE -> showDeleteConfirmDialog(R.string.dlmgr_delete_done_title) {
                deleteOne(group)
            }

            DoneAction.OPEN_AUTHOR -> openAuthor(group)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_list_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.list)
        (parentFragment as? DownloadManagerV3Fragment)?.let {
            list.setRecycledViewPool(it.doneRecycledViewPool)
        }
        list.adapter = adapter
        // 与 ActiveList / QueueList 及 V3 规范一致：关闭 DefaultItemAnimator，避免卡片与文字在刷新/上屏时整体淡入；仅保留缩略图自身的平滑过渡
        list.itemAnimator = null
        applyLayoutMode(list, DoneLayoutMode.fromInt(Shaft.sSettings.doneListLayoutMode))
        doneScrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && !recyclerView.canScrollVertically(1)) {
                    loadNextPage(recyclerView)
                }
            }
        }
        list.addOnScrollListener(doneScrollListener!!)

        val empty = view.findViewById<View>(R.id.emptyState)
        view.findViewById<TextView>(R.id.emptyTitle).text =
            getString(R.string.dlmgr_done_empty_title)
        view.findViewById<TextView>(R.id.emptyHint).text = getString(R.string.dlmgr_done_empty_hint)

        // btn1 改成"切换布局"按钮 —— 循环 LIST → GRID → COMPACT。
        // 用户首次发现这个按钮会顺便看到 toast 提示当前模式。
        val btnLayout = view.findViewById<Button>(R.id.btn1).apply {
            visibility = View.VISIBLE
            text = getString(DoneLayoutMode.fromInt(Shaft.sSettings.doneListLayoutMode).labelRes)
            setOnClickListener {
                val next = DoneLayoutMode.fromInt(Shaft.sSettings.doneListLayoutMode).next()
                Shaft.sSettings.doneListLayoutMode = next.ordinal
                Local.setSettings(Shaft.sSettings)
                applyLayoutMode(list, next)
                text = getString(next.labelRes)
            }
        }
        view.findViewById<Button>(R.id.btn2).visibility = View.GONE
        // 导出按钮已移到 host toolbar 的最右侧 menu (R.menu.menu_download_manager) ——
        // tab 0 / tab 2 通用入口，tab 1 隐藏。这里的 btn3 不再承担导出职责。
        view.findViewById<Button>(R.id.btn3).visibility = View.GONE
        view.findViewById<Button>(R.id.btn4).apply {
            text = getString(R.string.dlmgr_done_action_clear_history)
            setOnClickListener {
                // destructive 操作前必须确认。文案明确告知"文件不会被删除"，避免
                // 用户因恐慌而不敢清理记录。
                showDeleteConfirmDialog(R.string.dlmgr_clear_done_title) {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { dao.deleteAllDownload() }
                        ManagerReactive.pokeDoneTable()
                        // 顺手清掉 download_queue 里的 SUCCESS 行 —— 数字已经不依赖
                        // 这张表了(改为 illust_download_table 分组数),但 SUCCESS 行
                        // 也是历史垃圾,跟着一起清。
                        runCatching { queueDao.deleteByStatus(QueueStatus.SUCCESS) }
                        queueDownloadManager.queueListInvalidations.tryEmit(Unit)
                    }
                }
            }
        }

        // 监听 ManagerReactive.doneTableInvalidations：下载写入、导入、清空和删除
        // 后重新查询已加载的作品区间。搜索时对同一分组查询增加关键词过滤。
        //
        // 绑定在 STARTED 生命周期，并通过 sharedVm.selectedTab 精确闸门控制：
        // 1. 用户点击 Tab 2 时（t=0），onPageSelected 立刻置 selectedTab=2，利用 ViewPager2
        //    滑行的 360ms 黄金空窗期提前跑 DB 查询、聚合与首屏 ViewHolders 构建；
        // 2. 切离 Tab 2 时（selectedTab != 2），内部 flatMapLatest 自动切换到 emptyFlow()，
        //    彻底取消对 ManagerReactive.doneTableInvalidations 的收集，杜绝离屏期间被后台下载
        //    高频无效唤醒。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                runDoneListFlow(empty, list)
            }
        }

        // host toolbar 的导出 menu 通过 SharedFlow 通知子 fragment；只在 pos == 2
        // 时响应（已完成 tab）。tab 切换走时 STARTED 状态切到 RESUMED→STARTED→...,
        // STARTED 时 collect 还在跑，但其它 tab 的请求 (pos != 2) 会被过滤掉。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sharedVm.exportRequest.collect { pos ->
                    if (pos == 2) triggerExport()
                }
            }
        }
    }
    override fun onDestroyView() {
        doneScrollListener = null
        loadedGroups.clear()
        loadingNextPage = false
        pageGeneration++
        super.onDestroyView()
    }

    private data class DonePage(
        val groups: List<DownloadGroup>,
        val rowsRead: Int,
        val total: Int,
    )

    /** 数据库先按作品聚合，再分页；单作品即使超过 600 页也只占一个完整卡片。 */
    private fun readDonePage(query: String?, limit: Int, offset: Int): DonePage {
        val cards = dao.getDoneCards(Params.NOVEL_KEY, query, limit, offset)
        val entities = cards.map { it.fileName }.chunked(900)
            .flatMap { dao.getDownloadsByFileNames(it) }
            .associateBy { it.fileName }
        val groups = cards.mapNotNull { card ->
            val latest = entities[card.fileName] ?: return@mapNotNull null
            DownloadGroup(
                key = card.cardKey,
                latest = latest,
                pageCount = card.pageCount,
                isNovel = card.cardKey.startsWith("novel:"),
            )
        }
        return DonePage(groups, cards.size, dao.countDoneCards(Params.NOVEL_KEY, query))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runDoneListFlow(empty: View, list: RecyclerView) {
        sharedVm.selectedTab.flatMapLatest { tab ->
            if (tab != 2) {
                pageGeneration++
                hasMorePages = false
                emptyFlow()
            } else {
                sharedVm.doneSearchQuery.flatMapLatest { query ->
                    pageGeneration++
                    nextPageOffset = 0
                    hasMorePages = false
                    if (query == "") {
                        flowOf(DonePage(emptyList(), 0, 0))
                    } else {
                        ManagerReactive.doneTableInvalidations.map {
                            pageGeneration++
                            val limit = maxOf(PAGE_SIZE, nextPageOffset)
                            withContext(Dispatchers.IO) {
                                runCatching { readDonePage(query, limit, 0) }
                                    .getOrDefault(DonePage(emptyList(), 0, 0))
                            }
                        }
                    }
                }
            }
        }.collect { page ->
            loadedGroups.clear()
            loadedGroups.addAll(page.groups)
            nextPageOffset = page.rowsRead
            hasMorePages = page.rowsRead >= PAGE_SIZE && page.rowsRead < page.total
            empty.visibility = if (page.total == 0) View.VISIBLE else View.GONE
            sharedVm.publishDoneCardCount(page.total)
            submitDoneGroups(list)
        }
    }

    private fun submitDoneGroups(list: RecyclerView) {
        adapter.submitList(loadedGroups.toList()) {
            list.post {
                if (view != null && sharedVm.selectedTab.value == 2 && !list.canScrollVertically(1)) {
                    loadNextPage(list)
                }
            }
        }
    }

    private fun loadNextPage(list: RecyclerView) {
        val query = sharedVm.doneSearchQuery.value
        if (query == "" || !hasMorePages || loadingNextPage) return
        loadingNextPage = true
        val generation = pageGeneration
        val offset = nextPageOffset
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    runCatching { readDonePage(query, PAGE_SIZE, offset) }.getOrNull()
                }
                if (page != null && generation == pageGeneration && query == sharedVm.doneSearchQuery.value) {
                    loadedGroups.addAll(page.groups)
                    nextPageOffset = offset + page.rowsRead
                    hasMorePages = nextPageOffset < page.total && page.rowsRead > 0
                    sharedVm.publishDoneCardCount(page.total)
                    submitDoneGroups(list)
                }
            } finally {
                loadingNextPage = false
                // 刷新时旧请求可能占住 loadingNextPage，让新列表的自动续载错过机会。
                // 仅在代数变化时补一次；同代查询失败不能自动重试，否则会形成循环。
                if (generation != pageGeneration && view?.findViewById<RecyclerView>(R.id.list) === list &&
                    sharedVm.selectedTab.value == 2 && hasMorePages && !list.canScrollVertically(1)
                ) {
                    list.post {
                        if (generation != pageGeneration && view?.findViewById<RecyclerView>(R.id.list) === list &&
                            sharedVm.selectedTab.value == 2 && hasMorePages && !list.canScrollVertically(1)
                        ) {
                            loadNextPage(list)
                        }
                    }
                }
            }
        }
    }

    /**
     * 已完成 tab 的导出 — 把 [DownloadDao.getAll] 拉到的全部行按 illustId 分组
     * 去重（避免同一多 p illust 的 N 条 row 重复展开），跳过小说（NOVEL_KEY），
     * 然后每个 illust 通过 [originalUrlsOf] 展开成 N 个 original 直链。
     *
     * 导出的 .txt 用户拿来给第三方下载器/IDM 重抓缺漏页（4.57 → 4.6.x 重构
     * 丢的功能；用户反馈"保留 4.57 就为了导出"）。
     */
    private fun triggerExport() {
        viewLifecycleOwner.lifecycleScope.launch {
            val urls = withContext(Dispatchers.IO) {
                val rows = runCatching { dao.getAll(EXPORT_HARD_CAP, 0) }.getOrDefault(emptyList())
                groupByIllust(rows).flatMap { g ->
                    if (g.isNovel) return@flatMap emptyList()
                    g.parsedIllust?.let { originalUrlsOf(it) } ?: emptyList()
                }
            }
            DownloadExportLinks.present(this@DoneListV3Fragment, urls)
        }
    }

    private fun openDetail(group: DownloadGroup) {
        if (group.isNovel) {
            openNovel(group)
            return
        }
        val ugoira = group.parsedIllust?.takeIf { it.isGif() }
        if (ugoira != null) {
            openArtwork(ugoira)
            return
        }
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val illustId = group.key.removePrefix("illust:").toLongOrNull()
            val paths = if (illustId != null && group.key.startsWith("illust:")) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        dao.getDoneIllustPages(illustId, Params.NOVEL_KEY)
                            .sortedWith(DownloadedPageOrder)
                            .map { it.filePath.orEmpty() }
                    }.getOrNull()
                } ?: return@launch
            } else {
                listOf(group.latest.filePath.orEmpty())
            }
            val intent = Intent(ctx, ImageDetailActivity::class.java)
            intent.putExtra("illust", ArrayList(paths) as Serializable)
            intent.putExtra("dataType", "下载详情")
            intent.putExtra("index", 0)
            startActivity(intent)
        }
    }

    /**
     * 走 [VActivity] 打开单个作品 —— 与 [QueueListV3Fragment.openVActivity] 同一套：
     * 由它按 `isUseArtworkV3` 分发到 V3 / V2 详情页，不在这里各选一棵树。
     */
    private fun openArtwork(illust: Illust) {
        val ctx = context ?: return
        val pageData = PageData(listOf(illust))
        Container.get().addPageToMap(pageData)
        startActivity(
            Intent(ctx, VActivity::class.java).apply {
                putExtra(Params.POSITION, 0)
                putExtra(Params.PAGE_UUID, pageData.uuid)
            },
        )
    }

    // fileName 形如 "pixiv_shaft_novel_<id>"；老纪录 / Cache key 改名等异常时
    // 回退到 illustGson 里的 "id" 字段（Novel / loxia.Novel 都带）。
    private fun openNovel(group: DownloadGroup) {
        val novelId = extractNovelId(group.latest)
        if (novelId <= 0L) {
            Toaster.showShort(R.string.dlmgr_open_novel_failed)
            return
        }
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_READER.key)
            putExtra(Params.NOVEL_ID, novelId)
        }
        startActivity(intent)
    }

    // 点下载卡片的作者名 → 跳作者主页(UActivity)。下载记录都带完整 illustGson,
    // user.id 正常都在;解析失败 / 老记录拿不到 id 就静默不跳。
    private fun openAuthor(group: DownloadGroup) {
        val userId: Long = if (group.isNovel) {
            group.parsedNovel?.user?.id ?: 0L
        } else {
            group.parsedIllust?.user?.id ?: 0L
        }
        if (userId <= 0L) return
        startActivity(
            Intent(requireContext(), UActivity::class.java).putExtra(Params.USER_ID, userId),
        )
    }

    private fun showDeleteConfirmDialog(@StringRes titleRes: Int, onConfirm: () -> Unit) {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        WitDialog.MessageDialogBuilder(act)
            .setTitle(titleRes)
            .setMessage(R.string.dlmgr_clear_done_message)
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.sure, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                onConfirm()
            }
            .show()
    }

    /** 切换布局：换 LayoutManager + 通知 adapter 切 viewType 重 inflate。 */
    private fun applyLayoutMode(list: RecyclerView, mode: DoneLayoutMode) {
        list.layoutManager = when (mode) {
            DoneLayoutMode.LIST -> LinearLayoutManager(requireContext())
            DoneLayoutMode.GRID -> GridLayoutManager(requireContext(), 2)
            DoneLayoutMode.COMPACT -> GridLayoutManager(requireContext(), 4)
        }
        adapter.setMode(mode)
    }

    private fun deleteOne(group: DownloadGroup) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val illustId = group.key.removePrefix("illust:").toLongOrNull()
                if (illustId != null && group.key.startsWith("illust:")) {
                    dao.deleteDoneIllust(illustId, Params.NOVEL_KEY)
                } else {
                    dao.delete(group.latest)
                }
            }
            ManagerReactive.pokeDoneTable()
        }
    }

    companion object {
        private const val PAGE_SIZE = 100 // 作品数，而不是图片记录行数

        /** 导出时一次拉的硬上限 —— 5w 行 ≈ 2MB 文本，正常用户都装不了这么多 */
        private const val EXPORT_HARD_CAP = 50000

        /**
         * 根据当前设备屏幕可用高度和布局模式动态推算首屏所需的可见 Holder 数量。
         *
         * - LIST 模式：单列，单卡高约 96dp；
         * - GRID 模式：双列，单卡高约 240dp；
         * - COMPACT 模式：四列，单卡高约 104dp。
         * 向上取整加 1 行露头/滑动缓冲行，动态适配手机、平板、折叠屏及横屏。
         */
        internal fun estimateFirstScreenHolders(
            context: Context,
            mode: DoneLayoutMode,
        ): Int {
            val metrics = context.resources.displayMetrics
            val screenHeightDp = metrics.heightPixels / metrics.density
            val visibleHeightDp = (screenHeightDp - 150f).coerceAtLeast(300f)

            val (estimatedItemHeightDp, spanCount) = when (mode) {
                DoneLayoutMode.LIST -> 96f to 1
                DoneLayoutMode.GRID -> 240f to 2
                DoneLayoutMode.COMPACT -> 104f to 4
            }

            val visibleRows = ceil(visibleHeightDp / estimatedItemHeightDp).toInt() + 1
            return (visibleRows * spanCount).coerceIn(4, 32)
        }

        /**
         * 预构建首屏已完成项的 ViewHolder 并存入 pool，避开切页动画时的布局与 XML inflate 开销。
         * 在主线程空闲（IdleHandler）时分批（每次 2~4 个）构建，建满首屏可见数量后自动注销。
         * 返回值撤销尚未跑完的预热，调用方在 view 销毁时调用。
         */
        internal fun prewarmDoneHolders(
            context: Context,
            pool: RecyclerView.RecycledViewPool,
            mode: DoneLayoutMode,
            totalCount: Int = estimateFirstScreenHolders(context, mode),
            perBatch: Int = if (mode == DoneLayoutMode.COMPACT) 4 else 2,
        ): () -> Unit {
            val viewType = mode.ordinal
            pool.setMaxRecycledViews(viewType, maxOf(12, totalCount + 4))
            val dummyParent = RecyclerView(context).apply {
                layoutManager = when (mode) {
                    DoneLayoutMode.LIST -> LinearLayoutManager(context)
                    DoneLayoutMode.GRID -> GridLayoutManager(context, 2)
                    DoneLayoutMode.COMPACT -> GridLayoutManager(context, 4)
                }
            }
            var remaining = totalCount
            val layoutRes = when (mode) {
                DoneLayoutMode.LIST -> R.layout.cell_download_done_v3_list
                DoneLayoutMode.GRID -> R.layout.cell_download_done_v3
                DoneLayoutMode.COMPACT -> R.layout.cell_download_done_v3_compact
            }
            val inflater = LayoutInflater.from(context)
            // 必须使用 Adapter.createViewHolder，系统才会为 ViewHolder 赋予正确的 mItemViewType（否则默认为 -1 INVALID_TYPE，导致放入 pool 的是 -1 号桶而切页时取不出）
            val prewarmAdapter = object : RecyclerView.Adapter<DoneAdapterV3.VH>() {
                override fun onCreateViewHolder(parent: ViewGroup, vt: Int): DoneAdapterV3.VH {
                    val v = inflater.inflate(layoutRes, parent, false)
                    return DoneAdapterV3.VH(v)
                }
                override fun onBindViewHolder(holder: DoneAdapterV3.VH, position: Int) {}
                override fun getItemCount(): Int = 0
            }
            val idleHandler = MessageQueue.IdleHandler {
                runCatching {
                    val batch = minOf(perBatch, remaining)
                    repeat(batch) {
                        val vh = prewarmAdapter.createViewHolder(dummyParent, viewType)
                        pool.putRecycledView(vh)
                    }
                    remaining -= batch
                    remaining > 0
                }.getOrDefault(false)
            }
            val queue = Looper.myQueue()
            queue.addIdleHandler(idleHandler)
            return { queue.removeIdleHandler(idleHandler) }
        }
    }
}

// —— 分组聚合 —— 解决一个 illust N 页显示 N 卡的问题 ——

private val ILLUST_ID_REGEX = Regex("\"id\":(\\d+)")
private fun extractIllustId(json: String?): Long {
    if (json.isNullOrEmpty()) return -1L
    return ILLUST_ID_REGEX.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
}

private fun extractNovelId(entity: DownloadEntity): Long {
    val name = entity.fileName.orEmpty()
    val idx = name.indexOf(Params.NOVEL_KEY)
    if (idx >= 0) {
        val tail = name.substring(idx + Params.NOVEL_KEY.length)
        tail.toLongOrNull()?.let { return it }
    }
    return extractIllustId(entity.illustGson).takeIf { it > 0 } ?: -1L
}

internal data class DownloadGroup(
    val key: String,            // illustId 或 fileName（小说时）
    val latest: DownloadEntity, // 代表 entity（含 illustGson + 时间）
    val pageCount: Int,
    val isNovel: Boolean = false,
) {
    /** 惰性解析的 Illust —— 避免全量 fromJson 占用 CPU 并在首屏触发连续 GC */
    val parsedIllust: Illust? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (isNovel) null else runCatching {
            Shaft.sGson.fromJson(latest.illustGson, Illust::class.java)
        }.getOrNull()
    }

    /** 惰性解析的 Novel —— 同 [parsedIllust]，仅小说行需要 */
    val parsedNovel: Novel? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        if (isNovel) runCatching {
            Shaft.sGson.fromJson(latest.illustGson, Novel::class.java)
        }.getOrNull() else null
    }
}

private fun groupByIllust(rows: List<DownloadEntity>): List<DownloadGroup> {
    if (rows.isEmpty()) return emptyList()
    val buckets = LinkedHashMap<String, MutableList<DownloadEntity>>()
    for (row in rows) {
        val isNovel = row.fileName?.contains(Params.NOVEL_KEY) == true
        val key = if (isNovel) {
            "novel:${row.fileName.orEmpty()}"
        } else {
            val id = if (row.illustId > 0) row.illustId else extractIllustId(row.illustGson)
            if (id > 0) "illust:$id" else "anon:${row.fileName.orEmpty()}"
        }
        buckets.getOrPut(key) { mutableListOf() }.add(row)
    }
    // 导出只需每个作品最近的代表行；大图查看另按 id 查询完整页。
    val groups = buckets.entries.map { (k, list) ->
        val latest = list.maxByOrNull { it.downloadTime } ?: list.first()
        val isNovel = latest.fileName?.contains(Params.NOVEL_KEY) == true
        DownloadGroup(
            key = k,
            latest = latest,
            pageCount = list.size,
            isNovel = isNovel,
        )
    }
    // 按代表的 downloadTime 倒序
    return groups.sortedByDescending { it.latest.downloadTime }
}

private enum class DoneAction { OPEN, DELETE, OPEN_AUTHOR }

/**
 * 已完成 tab 三种布局模式。值与 [Settings.doneListLayoutMode] 同步：
 *   0 = LIST    横向列表，单列，缩略图在左
 *   1 = GRID    网格 2 列（旧默认）
 *   2 = COMPACT 紧凑缩图 4 列，无文字
 */
internal enum class DoneLayoutMode(val labelRes: Int) {
    LIST(R.string.dlmgr_done_layout_list),
    GRID(R.string.dlmgr_done_layout_grid),
    COMPACT(R.string.dlmgr_done_layout_compact);

    fun next(): DoneLayoutMode = values()[(ordinal + 1) % values().size]

    companion object {
        fun fromInt(i: Int): DoneLayoutMode = values().getOrElse(i) { GRID }
    }
}

private object DoneDiff : DiffUtil.ItemCallback<DownloadGroup>() {
    override fun areItemsTheSame(a: DownloadGroup, b: DownloadGroup): Boolean = a.key == b.key
    override fun areContentsTheSame(a: DownloadGroup, b: DownloadGroup): Boolean =
        a.pageCount == b.pageCount && a.latest.downloadTime == b.latest.downloadTime
}

private class DoneAdapterV3(
    initialMode: DoneLayoutMode,
    private val onAction: (DownloadGroup, DoneAction) -> Unit,
) : ListAdapter<DownloadGroup, DoneAdapterV3.VH>(DoneDiff) {

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private var mode: DoneLayoutMode = initialMode

    /** 封面 URL → 同一个 GlideUrl 实例，见 [glideUrlOf]。一屏 20 多张，128 够翻好几屏。 */
    private val glideUrls = LruCache<String, GlideUrl>(128)

    fun setMode(m: DoneLayoutMode) {
        if (mode == m) return
        mode = m
        // viewType 改了，必须 invalidate 让 RecyclerView 重 inflate 不同 cell
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = mode.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layoutRes = when (DoneLayoutMode.fromInt(viewType)) {
            DoneLayoutMode.LIST -> R.layout.cell_download_done_v3_list
            DoneLayoutMode.GRID -> R.layout.cell_download_done_v3
            DoneLayoutMode.COMPACT -> R.layout.cell_download_done_v3_compact
        }
        val v = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val group = getItem(pos)
        val entity = group.latest

        if (group.isNovel) {
            h.typeBadge.visibility = View.VISIBLE
            h.typeBadge.text = "NOVEL"
            // issue #876: DB 里 PK 是 NOVEL_KEY+id（仅是去重 key,不带标题）,
            // 真正的小说名/作者/封面从 illustGson 解出来的 Novel 里取;老纪录 /
            // 解析失败 fallback 回 fileName + 空封面,至少能看出是哪条记录。
            val novel = group.parsedNovel
            h.title.text = novel?.title?.takeIf { it.isNotBlank() }
                ?: entity.fileName.orEmpty()
            h.author.text = novel?.user?.name?.takeIf { it.isNotBlank() }
                ?.let { "by: $it" } ?: ""
            // 卡片 thumb 100–160dp,square_medium (~360px) 已足够清晰且体积更小
            val coverUrl = novel?.image_urls?.let {
                it.square_medium ?: it.medium ?: it.large
            }
            // 小说封面只有网络一条路（本地只有 .txt），每次重绑最贵 —— 统一走 bindThumb
            // 的「同 URL 复用 GlideUrl + 同 key 短路 + 当前图当占位」。
            bindThumb(h.thumb, null, coverUrl)
        } else {
            // 惰性解析：这张卡第一次绑定时才 fromJson（主线程，约 1ms），之后同一个 group 不再解析
            val illust: Illust? = group.parsedIllust
            // 多页 illust：左上角只显示 "Np"（去掉 "MANGA · " 冗余前缀）。
            // 单页 illust：徽章直接隐藏 —— 没有页数信息可言。
            // 之前的渐隐 + 透明背景文字在暗色图上几乎读不出，改为白字 + 70% 黑底。
            //
            // 动图不参与页数那套：它本来就没有「页」，而老版本留下的中间 zip 行和新的 GIF 行
            // 同属一个 illustId，分组后 group.pageCount 会是 2，照页数算法会渲染出骗人的「2P」。
            // 标成 GIF 也顺带说明了它点开去的是详情页而不是大图查看器。
            val badge: String? = when {
                illust?.isGif() == true -> "GIF"
                group.pageCount > 1 -> "${group.pageCount}P"
                else -> null
            }
            if (badge != null) {
                h.typeBadge.visibility = View.VISIBLE
                h.typeBadge.text = badge
            } else {
                h.typeBadge.visibility = View.GONE
            }
            h.title.text = illust?.title?.takeIf { it.isNotBlank() } ?: entity.fileName.orEmpty()
            h.author.text = illust?.user?.name?.let { "by: $it" } ?: ""
            // 缩略图优先 load 本地下载文件 —— 已完成 illust 本地就有完整图,
            // 走磁盘比回 pixiv CDN 快一个数量级且离线可用。
            // filePath 可能是 content:// (SAF) 或 file:// (legacy),Glide 都吃。
            // 文件丢了/无权限读 → .error() 链回退到 square_medium 网图。
            val localUri = entity.filePath
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            // 网图首选 square_medium (~360px),比 medium (~540px) 小,正方形也跟卡片裁切吻合
            val networkUrl = illust?.image_urls?.let {
                it.square_medium ?: it.medium ?: it.large
            }
            bindThumb(h.thumb, localUri, networkUrl)
        }

        h.time.text = entity.downloadTime.takeIf { it > 0 }?.let { timeFmt.format(Date(it)) } ?: ""

        h.itemView.setOnClickListener { onAction(group, DoneAction.OPEN) }
        // 点作者名单独跳作者主页(消费掉点击,不冒泡到卡片的打开作品)
        h.author.setOnClickListener { onAction(group, DoneAction.OPEN_AUTHOR) }
        h.deleteBtn.setOnClickListener { onAction(group, DoneAction.DELETE) }
        h.itemView.setOnLongClickListener {
            onAction(group, DoneAction.DELETE)
            true
        }
    }

    /**
     * 同一个 URL 复用同一个 [GlideUrl] 实例。
     *
     * Glide 的 EngineKey 用 `model.equals()` 判等（4.16 反编译确认），而 `GlideUrl.equals`
     * 是 `getCacheKey()` 相等 **且** `headers.equals()`。`GlideUrlChild` 的 headers 是每次构造
     * 现签的鉴权头（x-client-time 精确到秒），每次都是新的 lambda 实例、没有值语义 —— 同一个
     * URL 两次 load 永远不相等，EngineKey 随之不同，内存缓存就永远不命中，每次重绑都退回
     * 磁盘解码。
     *
     * 真机实测（已完成页 4 列密排，滚到底再猛滑回顶，48 次重绑）：小说封面 48/48 是
     * DATA_DISK_CACHE、中位 12ms（冷态 42ms、最差 250ms）；同一屏的插画缩略图走本地 Uri
     * （值语义相等）48/48 命中 MEMORY_CACHE、中位 1ms —— 这就是「只有小说缩略图在闪」的全部原因。
     *
     * 复用实例后 EngineKey 相等，重绑命中内存（1–2ms），猛滑不再整片重载。只在本适配器内缓存：
     * 同一条记录在列表里只出现一次，不会把并发的同 URL 请求并成一个 EngineJob。
     */
    private fun glideUrlOf(url: String): GlideUrl? {
        glideUrls.get(url)?.let { return it }
        return GlideUtil.getUrl(url)?.also { glideUrls.put(url, it) }
    }

    /**
     * 缩略图统一入口：本地文件优先、网图兜底，同一张图的重绑直接短路。
     *
     * `view.tag` 记「这张视图现在装的是谁」—— 同 key 且有图就整段跳过，连 Glide 请求都不发。
     * 重绑不再「先清空再加载」，这是「图→空→图」的根源。
     *
     * 不设占位图：key 变了说明是另一条记录，视图上现有的那张是别的作品，拿它当占位会在加载期间
     * （以及加载失败后，Glide 失败时回退显示占位图）把别人的封面配上这条的标题；那张图的资源
     * 也已随旧请求释放回缓存，可能被复用/回收。不设占位时加载中与失败都是 null drawable，
     * 上面的短路只会在真有图时生效，失败的格子下次重绑会重试。命中内存是在 into() 里同步返回的，
     * 不会有空白帧。
     */
    private fun bindThumb(view: ImageView, localUri: Uri?, networkUrl: String?) {
        val key = when {
            localUri != null -> "local:$localUri"
            !networkUrl.isNullOrEmpty() -> "net:$networkUrl"
            else -> null
        }
        if (key == null) {
            view.tag = null
            Glide.with(view).clear(view)
            view.setImageDrawable(null)
            return
        }
        if (view.tag == key && view.drawable != null) return
        view.tag = key

        var request = if (localUri != null) {
            Glide.with(view).load(localUri)
        } else {
            Glide.with(view).load(glideUrlOf(networkUrl.orEmpty()))
        }
        val crossFade = DrawableTransitionOptions.withCrossFade()
        // 本地文件丢了/无权限读时回退网图（与原逻辑一致）
        if (localUri != null && !networkUrl.isNullOrEmpty()) {
            request =
                request.error(Glide.with(view).load(glideUrlOf(networkUrl)).transition(crossFade))
        }
        // 缩略图淡入过渡：磁盘缓存（本地/外置存储）与网络加载时柔和显现，避免深色模式下亮色图突现引发明暗跳跃；内存缓存命中时 Glide 自动跳过过渡
        request.transition(crossFade).into(view)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val typeBadge: TextView = v.findViewById(R.id.typeBadge)
        val title: TextView = v.findViewById(R.id.title)
        val author: TextView = v.findViewById(R.id.author)
        val time: TextView = v.findViewById(R.id.time)
        val deleteBtn: ImageView = v.findViewById(R.id.deleteBtn)
    }
}
