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
 *  - 缩略图从 illust JSON 取（已下载文件路径不直接展示，统一用网络缩略图，避免 SAF 取本地文件复杂度）
 *  - 卡片包含：缩略图、type 徽章、标题、作者、时间
 *  - 点击进 ImageDetailActivity（复用旧逻辑），删除按钮 → 删 DB 记录（不删文件）
 */
class DoneListV3Fragment : Fragment() {

    private val dao: DownloadDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
    }
    private val adapter = DoneAdapterV3 { entity, action ->
        when (action) {
            DoneAction.OPEN -> openDetail(entity)
            DoneAction.DELETE -> deleteOne(entity)
        }
    }

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
        view.findViewById<TextView>(R.id.emptyTitle).text = "还没有完成的下载"
        view.findViewById<TextView>(R.id.emptyHint).text = "下载完成的作品会出现在这里"

        // 操作 bar：仅"清空记录"
        view.findViewById<Button>(R.id.btn1).visibility = View.GONE
        view.findViewById<Button>(R.id.btn2).visibility = View.GONE
        view.findViewById<Button>(R.id.btn3).apply {
            text = "刷新"
            setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch { reload() }
            }
        }
        view.findViewById<Button>(R.id.btn4).apply {
            text = "清空记录"
            setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { dao.deleteAllDownload() }
                    withContext(Dispatchers.Main) { reload() }
                }
            }
        }

        // 首次加载 + 监听 DOWNLOAD_FINISH 广播来插队 0
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                reload()
                empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                while (true) {
                    delay(REFRESH_INTERVAL_MS)
                    reload()
                    empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
                }
            }
        }

        // 实时插入新完成的项（顶到最前面）
        receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: Intent?) {
                viewLifecycleOwner.lifecycleScope.launch { reload() }
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
        val rows = withContext(Dispatchers.IO) {
            runCatching { dao.getAll(PAGE_SIZE, 0) }.getOrDefault(emptyList())
        }
        adapter.submitList(rows)
    }

    private fun openDetail(entity: DownloadEntity) {
        // 复用旧"下载详情"路由 —— 把所有当前 entity 的 filePath 收集起来
        val paths = ArrayList<String>()
        var index = 0
        for (i in 0 until adapter.itemCount) {
            val it = adapter.getItemAt(i)
            paths.add(it.filePath ?: "")
            if (it == entity) index = i
        }
        val intent = Intent(requireContext(), ImageDetailActivity::class.java)
        intent.putExtra("illust", paths as Serializable)
        intent.putExtra("dataType", "下载详情")
        intent.putExtra("index", index)
        startActivity(intent)
    }

    private fun deleteOne(entity: DownloadEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { dao.delete(entity) } }
            reload()
        }
    }

    companion object {
        private const val PAGE_SIZE = 200
        private const val REFRESH_INTERVAL_MS = 3000L
    }
}

private enum class DoneAction { OPEN, DELETE }

private object DoneDiff : DiffUtil.ItemCallback<DownloadEntity>() {
    override fun areItemsTheSame(a: DownloadEntity, b: DownloadEntity): Boolean = a.fileName == b.fileName
    override fun areContentsTheSame(a: DownloadEntity, b: DownloadEntity): Boolean =
        a.fileName == b.fileName && a.downloadTime == b.downloadTime
}

private class DoneAdapterV3(
    private val onAction: (DownloadEntity, DoneAction) -> Unit,
) : ListAdapter<DownloadEntity, DoneAdapterV3.VH>(DoneDiff) {

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun getItemAt(pos: Int): DownloadEntity = getItem(pos)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.cell_download_done_v3, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val entity = getItem(pos)

        val isNovel = entity.fileName?.contains(Params.NOVEL_KEY) == true
        if (isNovel) {
            h.typeBadge.text = "NOVEL"
            Glide.with(h.thumb).clear(h.thumb)
            h.thumb.setImageDrawable(null)
            h.title.text = entity.fileName.orEmpty()
            h.author.text = ""
        } else {
            val illust: IllustsBean? = runCatching {
                Shaft.sGson.fromJson(entity.illustGson, IllustsBean::class.java)
            }.getOrNull()
            h.typeBadge.text = if (illust?.page_count ?: 0 > 1) "MANGA" else "ILLUST"
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

        h.itemView.setOnClickListener { onAction(entity, DoneAction.OPEN) }
        h.deleteBtn.setOnClickListener { onAction(entity, DoneAction.DELETE) }
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
