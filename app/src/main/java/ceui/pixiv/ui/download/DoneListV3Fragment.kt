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
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
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
import ceui.lisa.utils.Params
import com.bumptech.glide.Glide
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
    private val adapter = DoneAdapterV3 { group, action ->
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
        list.layoutManager = GridLayoutManager(requireContext(), 2)
        list.adapter = adapter

        val empty = view.findViewById<View>(R.id.emptyState)
        view.findViewById<TextView>(R.id.emptyTitle).text = getString(R.string.dlmgr_done_empty_title)
        view.findViewById<TextView>(R.id.emptyHint).text = getString(R.string.dlmgr_done_empty_hint)

        view.findViewById<Button>(R.id.btn1).visibility = View.GONE
        view.findViewById<Button>(R.id.btn2).visibility = View.GONE
        view.findViewById<Button>(R.id.btn3).apply {
            text = getString(R.string.dlmgr_done_action_refresh)
            setOnClickListener { refreshTickle.trySend(Unit) }
        }
        view.findViewById<Button>(R.id.btn4).apply {
            text = getString(R.string.dlmgr_done_action_clear_history)
            setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { dao.deleteAllDownload() }
                    refreshTickle.trySend(Unit)
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

private object DoneDiff : DiffUtil.ItemCallback<DownloadGroup>() {
    override fun areItemsTheSame(a: DownloadGroup, b: DownloadGroup): Boolean = a.key == b.key
    override fun areContentsTheSame(a: DownloadGroup, b: DownloadGroup): Boolean =
        a.pageCount == b.pageCount && a.latest.downloadTime == b.latest.downloadTime
}

private class DoneAdapterV3(
    private val onAction: (DownloadGroup, DoneAction) -> Unit,
) : ListAdapter<DownloadGroup, DoneAdapterV3.VH>(DoneDiff) {

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.cell_download_done_v3, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val group = getItem(pos)
        val entity = group.latest

        if (group.isNovel) {
            h.typeBadge.text = "NOVEL"
            Glide.with(h.thumb).clear(h.thumb)
            h.thumb.setImageDrawable(null)
            h.title.text = entity.fileName.orEmpty()
            h.author.text = ""
        } else {
            // 用预解析的 illust（reload 时 IO 线程已 fromJson 完）—— 绑卡 0 解析
            val illust: IllustsBean? = group.parsedIllust
            // type 徽章：单页 ILLUST，多页 MANGA(N) 体现页数
            h.typeBadge.text = when {
                group.pageCount > 1 -> "MANGA · ${group.pageCount}P"
                (illust?.page_count ?: 1) > 1 -> "MANGA · ${illust?.page_count}P"
                else -> "ILLUST"
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
