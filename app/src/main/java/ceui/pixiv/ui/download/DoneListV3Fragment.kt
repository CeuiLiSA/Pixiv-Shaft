package ceui.pixiv.ui.download

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
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
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Local
import ceui.lisa.utils.Params
import ceui.loxia.Novel
import ceui.pixiv.db.queue.QueueStatus
import ceui.pixiv.ui.bulk.QueueDownloadManager
import com.bumptech.glide.Glide
import com.hjq.toast.Toaster
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V3 风格 "已完成" — 双列卡片网格。
 *
 * 关键 UX 修复（之前一个 illust 多页会显示 N 个相同卡片）：
 *   Manager 完成每个 page 都插入一条 DownloadEntity（PK=fileName）。
 *   3p 漫画 → 3 条记录 → 原本 3 张卡。
 *   现在按 illustId 分组聚合，1 张卡 + "Np" 角标显示总页数。
 *   保留最新 entity（按 downloadTime 取最大）作为代表，点击进图详情时
 *   传入该 illust 全部 page 的 filePath 数组，左右滑可看完整本。
 *
 * 数据源：[DownloadDao.flowAll] 是 Room reactive Flow ——
 * Manager 写 DownloadEntity 到 illust_download_table 时 InvalidationTracker
 * 自动 emit 新快照，UI 端 collect 即可。完全替代旧的 1.5s polling +
 * DOWNLOAD_FINISH 广播兜底架构（0 timer、0 broadcast receiver）。
 */
class DoneListV3Fragment : Fragment() {

    private val dao: DownloadDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
    }
    private val queueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }
    private val sharedVm: DownloadManagerSharedViewModel by activityViewModels()
    private val adapter = DoneAdapterV3(
        initialMode = DoneLayoutMode.fromInt(Shaft.sSettings.doneListLayoutMode),
    ) { group, action ->
        when (action) {
            DoneAction.OPEN -> openDetail(group)
            DoneAction.DELETE -> deleteOne(group)
            DoneAction.OPEN_AUTHOR -> openAuthor(group)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_list_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.adapter = adapter
        applyLayoutMode(list, DoneLayoutMode.fromInt(Shaft.sSettings.doneListLayoutMode))

        val empty = view.findViewById<View>(R.id.emptyState)
        view.findViewById<TextView>(R.id.emptyTitle).text = getString(R.string.dlmgr_done_empty_title)
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
                showClearDoneConfirmDialog {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { dao.deleteAllDownload() }
                        ManagerReactive.pokeDoneTable()
                        // 顺手清掉 download_queue 里的 SUCCESS 行 —— 数字已经不依赖
                        // 这张表了(改为 illust_download_table 分组数),但 SUCCESS 行
                        // 也是历史垃圾,跟着一起清。
                        runCatching { queueDao.deleteByStatus(QueueStatus.SUCCESS) }
                        QueueDownloadManager.queueListInvalidations.tryEmit(Unit)
                    }
                }
            }
        }

        // 数据源 = 默认走 ManagerReactive.doneTableInvalidations 脏标记。Manager
        // 写 illust_download_table / 用户清空 / 单条删除都 poke 一次。这边 collect
        // 后用 suspend dao.getAll(...) 拿最新行。
        //
        // ⚠️ 不用 dao.flowAll：Room InvalidationTracker 在连续 INSERT 序列下
        // 首次 emit 后静默不再 re-emit（同 download_queue 那个 bug）。
        //
        // 搜索模式：host toolbar 的 SearchView 输入会写到
        // sharedVm.doneSearchQuery（null=未进入搜索；""=已展开未输；非空=查询词）。
        // 切到搜索分支时数据源换成 dao.searchDownloads(query)。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                runDoneListFlow(empty)
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

    /**
     * 把"默认数据源"和"搜索数据源"合并成单一上游 — query 切换时 flatMapLatest
     * 自动 cancel 上一条数据源 collect，新订阅当前条件对应的 flow。两条分支都
     * 接 [ManagerReactive.doneTableInvalidations]（replay=1 SharedFlow，新订阅
     * 立即拿一帧），保证删一条 / 新增完成都能即时刷新列表。
     *
     * - query==null : 全量列表 → dao.getAll(PAGE_SIZE)
     * - query==""   : 搜索框展开但未输入 — 给空列表，让 empty state 提示
     * - query!=""   : 过滤列表 → dao.searchDownloads(query)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runDoneListFlow(empty: View) {
        sharedVm.doneSearchQuery
            .flatMapLatest { query ->
                when {
                    query == null -> ManagerReactive.doneTableInvalidations.map {
                        val rows = runCatching { dao.getAll(PAGE_SIZE, 0) }
                            .getOrDefault(emptyList())
                        groupByIllust(rows)
                    }
                    query.isEmpty() -> flow { emit(emptyList<DownloadGroup>()) }
                    else -> ManagerReactive.doneTableInvalidations.map {
                        val rows = runCatching { dao.searchDownloads(query) }
                            .getOrDefault(emptyList())
                        groupByIllust(rows)
                    }
                }
            }
            .flowOn(Dispatchers.IO)
            .collect { groups ->
                adapter.submitList(groups)
                empty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
                // tab 标题数字回填:数字必须 = 实际可见卡片数,否则会出现 "已完成
                // 1944 / 列表却为空" 这种数据源错位(参考 DownloadManagerSharedViewModel.doneCardCount)。
                // 搜索中也回填，让 tab 显示当前过滤命中数。
                sharedVm.publishDoneCardCount(groups.size)
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
        // 动图和小说一样得单独分流：ImageDetailActivity 是静态大图查看器，喂它一个 GIF
        // （更早是喂中间 zip）只会得到一片黑（issue #920）。回作品详情页，由页面内联的
        // UgoiraPlayerView 正常逐帧播放。
        //
        // 只认 illustGson 里 type=ugoira 的**完整** bean。DownloadImporter 扫进来的行
        // illustGson 是 `{"id":N,"shaft_imported":true}`，isGif 判不出来也 visible=false，
        // 送进 VActivity 会走它的「不可见作品」兜底分支去读空的 image_urls；那些行本来就
        // 指着真正的 .gif 文件，留在原来的查看器里反而是能看的。
        val ugoira = group.parsedIllust?.takeIf { it.isGif }
        if (ugoira != null) {
            openArtwork(ugoira)
            return
        }
        // 取该 illust 全部 page 的 filePath（按 fileName 自然顺序）
        val paths: ArrayList<String> = ArrayList(group.allFilePaths)
        val intent = Intent(requireContext(), ImageDetailActivity::class.java)
        intent.putExtra("illust", paths as Serializable)
        intent.putExtra("dataType", "下载详情")
        intent.putExtra("index", 0)
        startActivity(intent)
    }

    /**
     * 走 [VActivity] 打开单个作品 —— 与 [QueueListV3Fragment.openVActivity] 同一套：
     * 由它按 `isUseArtworkV3` 分发到 V3 / V2 详情页，不在这里各选一棵树。
     */
    private fun openArtwork(illust: IllustsBean) {
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
    // 回退到 illustGson 里的 "id" 字段（NovelBean / loxia.Novel 都带）。
    private fun openNovel(group: DownloadGroup) {
        val novelId = extractNovelId(group.latest)
        if (novelId <= 0L) {
            Toaster.showShort(R.string.dlmgr_open_novel_failed)
            return
        }
        val intent = Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(TemplateActivity.EXTRA_FRAGMENT, "小说正文")
            putExtra(Params.NOVEL_ID, novelId)
        }
        startActivity(intent)
    }

    // 点下载卡片的作者名 → 跳作者主页(UActivity)。下载记录都带完整 illustGson,
    // user.id 正常都在;解析失败 / 老记录拿不到 id 就静默不跳。
    private fun openAuthor(group: DownloadGroup) {
        val userId: Int = if (group.isNovel) {
            group.parsedNovel?.user?.id?.toInt() ?: 0
        } else {
            group.parsedIllust?.user?.id ?: 0
        }
        if (userId <= 0) return
        startActivity(
            Intent(requireContext(), UActivity::class.java).putExtra(Params.USER_ID, userId),
        )
    }

    private fun showClearDoneConfirmDialog(onConfirm: () -> Unit) {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        QMUIDialog.MessageDialogBuilder(act)
            .setTitle(R.string.dlmgr_clear_done_title)
            .setMessage(R.string.dlmgr_clear_done_message)
            .setSkinManager(QMUISkinManager.defaultInstance(act))
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.sure, QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                onConfirm()
            }
            .show()
    }

    /** 切换布局：换 LayoutManager + 通知 adapter 切 viewType 重 inflate。 */
    private fun applyLayoutMode(list: RecyclerView, mode: DoneLayoutMode) {
        list.layoutManager = when (mode) {
            DoneLayoutMode.LIST    -> LinearLayoutManager(requireContext())
            DoneLayoutMode.GRID    -> GridLayoutManager(requireContext(), 2)
            DoneLayoutMode.COMPACT -> GridLayoutManager(requireContext(), 4)
        }
        adapter.setMode(mode)
    }

    private fun deleteOne(group: DownloadGroup) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                group.allEntities.forEach { dao.delete(it) }
            }
            ManagerReactive.pokeDoneTable()
        }
    }

    companion object {
        private const val PAGE_SIZE = 600   // 一次取多点；分组后实际卡片数会少
        /** 导出时一次拉的硬上限 —— 5w 行 ≈ 2MB 文本，正常用户都装不了这么多 */
        private const val EXPORT_HARD_CAP = 50000
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
    val allFilePaths: List<String>,
    val allEntities: List<DownloadEntity>,
    /** 预解析的 IllustsBean —— 在 IO 线程做完 Gson；UI 绑卡时直接用，不再 fromJson 卡帧 */
    val parsedIllust: IllustsBean? = null,
    /** 预解析的 loxia Novel —— 同 [parsedIllust]，仅小说行有值。issue #876:
     *  DB 里 fileName 是 PK（NOVEL_KEY+id），不带标题；下载记录卡片要从这里
     *  取真正的小说名 + 作者展示。 */
    val parsedNovel: Novel? = null,
    val isNovel: Boolean = false,
)

private fun groupByIllust(rows: List<DownloadEntity>): List<DownloadGroup> {
    if (rows.isEmpty()) return emptyList()
    val buckets = LinkedHashMap<String, MutableList<DownloadEntity>>()
    for (row in rows) {
        val isNovel = row.fileName?.contains(Params.NOVEL_KEY) == true
        val key = if (isNovel) {
            "novel:${row.fileName.orEmpty()}"
        } else {
            val id = extractIllustId(row.illustGson)
            if (id > 0) "illust:$id" else "anon:${row.fileName.orEmpty()}"
        }
        buckets.getOrPut(key) { mutableListOf() }.add(row)
    }
    // 每组按 fileName 自然排序（p0, p1, p2…），代表 entity 取 downloadTime 最大的；
    // Gson.fromJson 在这里（IO 线程）就解掉，绑卡时不再 parse
    val groups = buckets.entries.map { (k, list) ->
        val sortedByName = list.sortedBy { it.fileName.orEmpty() }
        val latest = list.maxByOrNull { it.downloadTime } ?: list.first()
        val isNovel = latest.fileName?.contains(Params.NOVEL_KEY) == true
        val parsedIllust = if (isNovel) null else runCatching {
            Shaft.sGson.fromJson(latest.illustGson, IllustsBean::class.java)
        }.getOrNull()
        val parsedNovel = if (isNovel) runCatching {
            Shaft.sGson.fromJson(latest.illustGson, Novel::class.java)
        }.getOrNull() else null
        DownloadGroup(
            key = k,
            latest = latest,
            pageCount = list.size,
            allFilePaths = sortedByName.map { it.filePath.orEmpty() },
            allEntities = sortedByName,
            parsedIllust = parsedIllust,
            parsedNovel = parsedNovel,
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

    fun setMode(m: DoneLayoutMode) {
        if (mode == m) return
        mode = m
        // viewType 改了，必须 invalidate 让 RecyclerView 重 inflate 不同 cell
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = mode.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layoutRes = when (DoneLayoutMode.fromInt(viewType)) {
            DoneLayoutMode.LIST    -> R.layout.cell_download_done_v3_list
            DoneLayoutMode.GRID    -> R.layout.cell_download_done_v3
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
            if (!coverUrl.isNullOrEmpty()) {
                Glide.with(h.thumb)
                    .load(GlideUtil.getUrl(coverUrl))
                    .placeholder(android.R.color.transparent)
                    .into(h.thumb)
            } else {
                Glide.with(h.thumb).clear(h.thumb)
                h.thumb.setImageDrawable(null)
            }
        } else {
            // 用预解析的 illust（reload 时 IO 线程已 fromJson 完）—— 绑卡 0 解析
            val illust: IllustsBean? = group.parsedIllust
            // 多页 illust：左上角只显示 "Np"（去掉 "MANGA · " 冗余前缀）。
            // 单页 illust：徽章直接隐藏 —— 没有页数信息可言。
            // 之前的渐隐 + 透明背景文字在暗色图上几乎读不出，改为白字 + 70% 黑底。
            //
            // 动图不参与页数那套：它本来就没有「页」，而老版本留下的中间 zip 行和新的 GIF 行
            // 同属一个 illustId，分组后 group.pageCount 会是 2，照页数算法会渲染出骗人的「2P」。
            // 标成 GIF 也顺带说明了它点开去的是详情页而不是大图查看器。
            val badge: String? = when {
                illust?.isGif == true -> "GIF"
                group.pageCount > 1 -> "${group.pageCount}P"
                (illust?.page_count ?: 1) > 1 -> "${illust?.page_count ?: 1}P"
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
            val networkLoader = if (!networkUrl.isNullOrEmpty()) {
                Glide.with(h.thumb)
                    .load(GlideUtil.getUrl(networkUrl))
                    .placeholder(android.R.color.transparent)
            } else null
            when {
                localUri != null -> {
                    Glide.with(h.thumb)
                        .load(localUri)
                        .placeholder(android.R.color.transparent)
                        .let { if (networkLoader != null) it.error(networkLoader) else it }
                        .into(h.thumb)
                }
                networkLoader != null -> networkLoader.into(h.thumb)
                else -> {
                    Glide.with(h.thumb).clear(h.thumb)
                    h.thumb.setImageDrawable(null)
                }
            }
        }

        h.time.text = entity.downloadTime.takeIf { it > 0 }?.let { timeFmt.format(Date(it)) } ?: ""

        h.itemView.setOnClickListener { onAction(group, DoneAction.OPEN) }
        // 点作者名单独跳作者主页(消费掉点击,不冒泡到卡片的打开作品)
        h.author.setOnClickListener { onAction(group, DoneAction.OPEN_AUTHOR) }
        h.deleteBtn.setOnClickListener { onAction(group, DoneAction.DELETE) }
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
