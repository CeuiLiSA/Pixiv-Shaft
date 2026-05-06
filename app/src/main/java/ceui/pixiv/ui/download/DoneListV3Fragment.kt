package ceui.pixiv.ui.download

import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.ImageDetailActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.database.DownloadDao
import ceui.lisa.database.DownloadEntity
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Local
import ceui.lisa.utils.Params
import com.bumptech.glide.Glide
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 * 触发刷新策略：
 *   - 1.5s 周期 polling（仅 STARTED）
 *   - DOWNLOAD_FINISH 广播 → conflated channel 合并 → 单次防抖 reload
 */
class DoneListV3Fragment : Fragment() {

    private val dao: DownloadDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
    }
    private val adapter = DoneAdapterV3(
        initialMode = DoneLayoutMode.fromInt(Shaft.sSettings.doneListLayoutMode),
    ) { group, action ->
        when (action) {
            DoneAction.OPEN -> openDetail(group)
            DoneAction.DELETE -> deleteOne(group)
        }
    }
    private val refreshTickle = Channel<Unit>(Channel.CONFLATED)

    private var receiver: android.content.BroadcastReceiver? = null

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
        // btn3 改为"导出"：把已完成下载的所有 illustId 拼成 pixiv 链接列表，
        // 走 ACTION_SEND 让用户分享/保存。4.6.4 之前的 FragmentMultiDownload
        // 有这个功能，重写后丢了，现在补回来。
        view.findViewById<Button>(R.id.btn3).apply {
            visibility = View.VISIBLE
            text = getString(R.string.dlmgr_done_action_export)
            setOnClickListener { exportDoneList() }
        }
        view.findViewById<Button>(R.id.btn4).apply {
            text = getString(R.string.dlmgr_done_action_clear_history)
            setOnClickListener {
                // destructive 操作前必须确认。文案明确告知"文件不会被删除"，避免
                // 用户因恐慌而不敢清理记录。
                showClearDoneConfirmDialog {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { dao.deleteAllDownload() }
                        refreshTickle.trySend(Unit)
                    }
                }
            }
        }

        // 周期 + 触发型 reload
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                reload()
                empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                while (true) {
                    // 谁先：tickle / 1500ms 超时；CONFLATED channel 把多次广播合并成一次唤醒
                    try {
                        kotlinx.coroutines.withTimeout(REFRESH_INTERVAL_MS) {
                            refreshTickle.receive()
                        }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        /* 超时 = 周期性刷新 */
                    }
                    reload()
                    empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                }
            }
        }

        // DOWNLOAD_FINISH 广播 → 合并到 tickle channel（不直接 reload，避免 N 页 N 次查询）
        receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: Intent?) {
                refreshTickle.trySend(Unit)
            }
        }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(receiver!!, IntentFilter(Params.DOWNLOAD_FINISH))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        receiver?.let {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(it)
        }
        receiver = null
    }

    private suspend fun reload() {
        val groups = withContext(Dispatchers.IO) {
            val rows = runCatching { dao.getAll(PAGE_SIZE, 0) }.getOrDefault(emptyList())
            groupByIllust(rows)
        }
        adapter.submitList(groups)
    }

    private fun openDetail(group: DownloadGroup) {
        // 取该 illust 全部 page 的 filePath（按 fileName 自然顺序）
        val paths: ArrayList<String> = ArrayList(group.allFilePaths)
        val intent = Intent(requireContext(), ImageDetailActivity::class.java)
        intent.putExtra("illust", paths as Serializable)
        intent.putExtra("dataType", "下载详情")
        intent.putExtra("index", 0)
        startActivity(intent)
    }

    /**
     * 导出已完成下载的链接列表为纯文本，走 [Intent.ACTION_SEND] 让用户选择
     * 复制 / 保存为文件 / 发送到聊天工具。每行一个 pixiv 作品链接，按下载时间倒序，
     * 按 illustId 去重（多 p illust 只占一行）。
     *
     * 不导出小说（NOVEL_KEY 标记的行）—— 小说没有标准 URL 模板，需要单独处理。
     */
    private fun exportDoneList() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val (text, illustCount) = withContext(Dispatchers.IO) {
                val rows = runCatching { dao.getAll(EXPORT_HARD_CAP, 0) }.getOrDefault(emptyList())
                val groups = groupByIllust(rows)
                val sb = StringBuilder()
                var n = 0
                for (g in groups) {
                    if (g.isNovel) continue
                    // 优先用预解析的 illust.id，回退到正则从 illustGson 抽 id
                    val id = (g.parsedIllust?.id?.toLong()?.takeIf { it > 0 })
                        ?: extractIllustId(g.latest.illustGson).takeIf { it > 0 }
                        ?: continue
                    sb.append("https://www.pixiv.net/artworks/").append(id).append('\n')
                    n++
                }
                sb.toString().trimEnd() to n
            }
            if (text.isEmpty()) {
                Toast.makeText(ctx, getString(R.string.dlmgr_done_export_empty), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val title = getString(R.string.dlmgr_done_export_share_title)
            val summary = getString(R.string.dlmgr_done_export_summary, illustCount)
            Toast.makeText(ctx, summary, Toast.LENGTH_SHORT).show()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, title))
        }
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
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // 删除该 illust 下所有 page 的记录
                runCatching {
                    group.allEntities.forEach { dao.delete(it) }
                }
            }
            reload()
        }
    }

    companion object {
        private const val PAGE_SIZE = 600   // 一次取多点；分组后实际卡片数会少
        private const val REFRESH_INTERVAL_MS = 1500L
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

internal data class DownloadGroup(
    val key: String,            // illustId 或 fileName（小说时）
    val latest: DownloadEntity, // 代表 entity（含 illustGson + 时间）
    val pageCount: Int,
    val allFilePaths: List<String>,
    val allEntities: List<DownloadEntity>,
    /** 预解析的 IllustsBean —— 在 IO 线程做完 Gson；UI 绑卡时直接用，不再 fromJson 卡帧 */
    val parsedIllust: IllustsBean? = null,
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
        val parsed = if (isNovel) null else runCatching {
            Shaft.sGson.fromJson(latest.illustGson, IllustsBean::class.java)
        }.getOrNull()
        DownloadGroup(
            key = k,
            latest = latest,
            pageCount = list.size,
            allFilePaths = sortedByName.map { it.filePath.orEmpty() },
            allEntities = sortedByName,
            parsedIllust = parsed,
            isNovel = isNovel,
        )
    }
    // 按代表的 downloadTime 倒序
    return groups.sortedByDescending { it.latest.downloadTime }
}

private enum class DoneAction { OPEN, DELETE }

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
            Glide.with(h.thumb).clear(h.thumb)
            h.thumb.setImageDrawable(null)
            h.title.text = entity.fileName.orEmpty()
            h.author.text = ""
        } else {
            // 用预解析的 illust（reload 时 IO 线程已 fromJson 完）—— 绑卡 0 解析
            val illust: IllustsBean? = group.parsedIllust
            // 多页 illust：左上角只显示 "Np"（去掉 "MANGA · " 冗余前缀）。
            // 单页 illust：徽章直接隐藏 —— 没有页数信息可言。
            // 之前的渐隐 + 透明背景文字在暗色图上几乎读不出，改为白字 + 70% 黑底。
            val pageCount = when {
                group.pageCount > 1 -> group.pageCount
                (illust?.page_count ?: 1) > 1 -> illust?.page_count ?: 1
                else -> 1
            }
            if (pageCount > 1) {
                h.typeBadge.visibility = View.VISIBLE
                h.typeBadge.text = "${pageCount}P"
            } else {
                h.typeBadge.visibility = View.GONE
            }
            h.title.text = illust?.title?.takeIf { it.isNotBlank() } ?: entity.fileName.orEmpty()
            h.author.text = illust?.user?.name?.let { "by: $it" } ?: ""
            val showUrl = illust?.image_urls?.medium
            if (!showUrl.isNullOrEmpty()) {
                Glide.with(h.thumb)
                    .load(GlideUtil.getUrl(showUrl))
                    .placeholder(android.R.color.transparent)
                    .into(h.thumb)
            } else {
                Glide.with(h.thumb).clear(h.thumb)
                h.thumb.setImageDrawable(null)
            }
        }

        h.time.text = entity.downloadTime.takeIf { it > 0 }?.let { timeFmt.format(Date(it)) } ?: ""

        h.itemView.setOnClickListener { onAction(group, DoneAction.OPEN) }
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
