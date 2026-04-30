package ceui.pixiv.ui.download

import android.graphics.Color
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.utils.GlideUtil
import ceui.loxia.ObjectPool
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.DownloadQueueEntity
import ceui.pixiv.db.queue.QueueStatus
import ceui.pixiv.ui.bulk.QueueDownloadManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V3 风格 "批量队列" 列表。
 *
 * 设计：
 *  - 顶部胶囊式操作 bar（暂停/继续, 重试失败, 清空成功, 清空全部）
 *  - 卡片化（CardView 圆角 18dp，elevation 1.5dp，背景 v3_bg）
 *  - 缩略图从 ObjectPool 取（命中率高，最近抓的都在）；不在池里就只显示占位色
 *  - 状态徽章彩色：PENDING(灰)/DOWNLOADING(蓝)/SUCCESS(绿)/FAILED(红)
 */
class QueueListV3Fragment : Fragment() {

    private val dao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }
    private val adapter = QueueAdapterV3()
    @Volatile private var paused = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_list_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.setHasFixedSize(true)

        val empty = view.findViewById<View>(R.id.emptyState)
        view.findViewById<TextView>(R.id.emptyTitle).text = "队列为空"
        view.findViewById<TextView>(R.id.emptyHint).text = "去作者页 → 右上角 more →\n下载全部插画 / 漫画"

        val btnPause = view.findViewById<Button>(R.id.btn1).apply { text = "暂停" }
        val btnRetry = view.findViewById<Button>(R.id.btn2).apply { text = "重试失败" }
        val btnClearOk = view.findViewById<Button>(R.id.btn3).apply { text = "清空成功" }
        val btnClearAll = view.findViewById<Button>(R.id.btn4).apply { text = "清空全部" }

        btnPause.setOnClickListener {
            paused = !paused
            if (paused) QueueDownloadManager.pause() else QueueDownloadManager.resume()
            btnPause.text = if (paused) "继续" else "暂停"
        }
        btnRetry.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                runCatching { dao.retryAllFailed() }
                QueueDownloadManager.notifyNewItems()
            }
        }
        btnClearOk.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                runCatching { dao.deleteByStatus(QueueStatus.SUCCESS) }
            }
        }
        btnClearAll.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                runCatching { dao.deleteAll() }
            }
        }

        // 仅 STARTED 时刷新
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val rows = withContext(Dispatchers.IO) {
                        runCatching { dao.page(limit = PAGE_SIZE, offset = 0) }.getOrDefault(emptyList())
                    }
                    adapter.submitList(rows)
                    empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 200
        private const val REFRESH_INTERVAL_MS = 1500L
    }
}

private object QueueDiff : DiffUtil.ItemCallback<DownloadQueueEntity>() {
    override fun areItemsTheSame(a: DownloadQueueEntity, b: DownloadQueueEntity): Boolean = a.id == b.id
    override fun areContentsTheSame(a: DownloadQueueEntity, b: DownloadQueueEntity): Boolean =
        a.status == b.status && a.retryCount == b.retryCount
}

private class QueueAdapterV3 : ListAdapter<DownloadQueueEntity, QueueAdapterV3.VH>(QueueDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.cell_download_queue_v3, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = getItem(pos)
        // 优先从 ObjectPool 拿 illust，命中显示标题、作者、缩略图
        val illust = runCatching { ObjectPool.getIllust(item.illustId).value }.getOrNull()
        if (illust != null) {
            h.title.text = illust.title.orEmpty().ifBlank { "illustId ${item.illustId}" }
            h.author.text = illust.user?.name?.let { "by: $it" } ?: ""
            val showUrl = runCatching { illust.image_urls?.medium }.getOrNull()
            if (!showUrl.isNullOrEmpty()) {
                Glide.with(h.thumb)
                    .load(GlideUtil.getUrl(showUrl))
                    .placeholder(android.R.color.transparent)
                    .into(h.thumb)
            } else {
                Glide.with(h.thumb).clear(h.thumb)
                h.thumb.setImageDrawable(null)
            }
        } else {
            h.title.text = "illust  ${item.illustId}"
            h.author.text = ""
            Glide.with(h.thumb).clear(h.thumb)
            h.thumb.setImageDrawable(null)
        }

        h.seqLabel.text = "#${pos + 1}  ·  ${item.type}"

        h.statusBadge.text = when (item.status) {
            QueueStatus.PENDING -> "PENDING"
            QueueStatus.DOWNLOADING -> "DOWNLOADING"
            QueueStatus.SUCCESS -> "SUCCESS"
            QueueStatus.FAILED -> "FAILED"
            else -> item.status
        }
        h.statusBadge.setTextColor(
            when (item.status) {
                QueueStatus.PENDING -> Color.parseColor("#9DA3AB")
                QueueStatus.DOWNLOADING -> Color.parseColor("#5EB3FF")
                QueueStatus.SUCCESS -> Color.parseColor("#7CB668")
                QueueStatus.FAILED -> Color.parseColor("#FF8B8B")
                else -> Color.GRAY
            }
        )

        if (item.retryCount > 0) {
            h.retryBadge.visibility = View.VISIBLE
            h.retryBadge.text = "RETRY ${item.retryCount}"
        } else {
            h.retryBadge.visibility = View.GONE
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val author: TextView = v.findViewById(R.id.author)
        val seqLabel: TextView = v.findViewById(R.id.seqLabel)
        val statusBadge: TextView = v.findViewById(R.id.statusBadge)
        val retryBadge: TextView = v.findViewById(R.id.retryBadge)
    }
}
