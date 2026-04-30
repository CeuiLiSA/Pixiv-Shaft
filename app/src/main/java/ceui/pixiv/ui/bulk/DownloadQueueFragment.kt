package ceui.pixiv.ui.bulk

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
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
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.DownloadQueueEntity
import ceui.pixiv.db.queue.QueueStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 批量下载队列 UI（替换旧 FragmentMultiDownload）。
 *
 * 性能策略：
 *  - 列表用分页（[PAGE_SIZE]）查询，避免上万条一次拉取卡顿。
 *  - 刷新仅在 [Lifecycle.State.STARTED] 时进行；切走或后台不刷新（[repeatOnLifecycle]）。
 *  - 使用 [ListAdapter] + [DiffUtil]，比 notifyDataSetChanged 更省。
 *  - 4 个 count + 1 个 page 查询走 IO；UI 线程只 setText / submitList。
 */
class DownloadQueueFragment : Fragment() {

    // 懒加载：避免 onViewCreated 在 Main 线程触发 DB 打开 / migration
    private val dao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }
    private lateinit var statsLine: TextView
    private lateinit var list: RecyclerView
    private lateinit var btnPauseResume: Button
    private lateinit var btnRetryFailed: Button
    private lateinit var btnClearSuccess: Button
    private lateinit var btnClearAll: Button
    private val adapter = QueueAdapter()
    private var paused = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_download_queue, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        statsLine = view.findViewById(R.id.statsLine)
        list = view.findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.setHasFixedSize(true)

        btnPauseResume = view.findViewById(R.id.btnPauseResume)
        btnRetryFailed = view.findViewById(R.id.btnRetryFailed)
        btnClearSuccess = view.findViewById(R.id.btnClearSuccess)
        btnClearAll = view.findViewById(R.id.btnClearAll)

        btnPauseResume.setOnClickListener {
            paused = !paused
            if (paused) QueueDownloadManager.pause() else QueueDownloadManager.resume()
            btnPauseResume.text = if (paused) "继续" else "暂停"
        }
        btnRetryFailed.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                dao.retryAllFailed()
                QueueDownloadManager.notifyNewItems()
            }
        }
        btnClearSuccess.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { dao.deleteByStatus(QueueStatus.SUCCESS) }
        }
        btnClearAll.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { dao.deleteAll() }
        }

        // 仅 STARTED 时刷新；切走 / 后台 / 屏幕关闭都自动停止；恢复时自动重新启动。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    kotlinx.coroutines.delay(REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun refresh() {
        val stats = withContext(Dispatchers.IO) {
            Stats(
                pending = dao.countByStatus(QueueStatus.PENDING),
                downloading = dao.countByStatus(QueueStatus.DOWNLOADING),
                success = dao.countByStatus(QueueStatus.SUCCESS),
                failed = dao.countByStatus(QueueStatus.FAILED),
                page = dao.page(limit = PAGE_SIZE, offset = 0),
            )
        }
        statsLine.text = "待下载: ${stats.pending} · 下载中: ${stats.downloading} · 成功: ${stats.success} · 失败: ${stats.failed}"
        adapter.submitList(stats.page)
    }

    private data class Stats(
        val pending: Int,
        val downloading: Int,
        val success: Int,
        val failed: Int,
        val page: List<DownloadQueueEntity>,
    )

    companion object {
        private const val PAGE_SIZE = 200
        private const val REFRESH_INTERVAL_MS = 2000L
    }
}

private object QueueDiff : DiffUtil.ItemCallback<DownloadQueueEntity>() {
    override fun areItemsTheSame(a: DownloadQueueEntity, b: DownloadQueueEntity): Boolean = a.id == b.id
    override fun areContentsTheSame(a: DownloadQueueEntity, b: DownloadQueueEntity): Boolean =
        a.status == b.status && a.retryCount == b.retryCount && a.errorMsg == b.errorMsg
}

private class QueueAdapter : ListAdapter<DownloadQueueEntity, QueueAdapter.VH>(QueueDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.cell_download_queue_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = getItem(pos)
        h.seq.text = "#${pos + 1}"
        val retrySuffix = if (item.retryCount > 0) "  retry=${item.retryCount}" else ""
        h.illustId.text = "illustId ${item.illustId}  [${item.type}]$retrySuffix"
        h.status.text = item.status
        h.status.setTextColor(
            when (item.status) {
                QueueStatus.PENDING -> Color.parseColor("#9DA3AB")
                QueueStatus.DOWNLOADING -> Color.parseColor("#5EB3FF")
                QueueStatus.SUCCESS -> Color.parseColor("#7CB668")
                QueueStatus.FAILED -> Color.parseColor("#FF8B8B")
                else -> Color.GRAY
            }
        )
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val seq: TextView = v.findViewById(R.id.seqText)
        val illustId: TextView = v.findViewById(R.id.illustIdText)
        val status: TextView = v.findViewById(R.id.statusBadge)
    }
}
