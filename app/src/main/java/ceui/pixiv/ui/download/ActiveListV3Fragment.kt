package ceui.pixiv.ui.download

import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.Manager
import ceui.lisa.download.DownloadProgress
import ceui.lisa.download.FileSizeUtil
import ceui.lisa.notification.DownloadReceiver
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.ui.bulk.QueueDownloadManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * V3 风格 "正在下载" — 监听 Manager.content。
 *
 *  - 1s polling 取 snapshot（不依赖 broadcast，规避 RxJava 老回调路径）；
 *    UI 真正高频刷新走 Manager.setCallback 的进度回调，per-item 更新当前活动项 progress。
 *  - 卡片包含缩略图、文件名、size、进度条、暂停/取消。
 */
class ActiveListV3Fragment : Fragment() {

    private val adapter = ActiveAdapterV3()
    private var receiver: DownloadReceiver<*>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_list_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.setHasFixedSize(false)

        val empty = view.findViewById<View>(R.id.emptyState)
        view.findViewById<TextView>(R.id.emptyTitle).text = "没有正在下载的任务"
        view.findViewById<TextView>(R.id.emptyHint).text = "队列里的任务会自动转到这里\n显示 page 级实时进度"

        // 操作 bar
        view.findViewById<Button>(R.id.btn1).apply {
            text = "全部继续"
            setOnClickListener { Manager.get().startAll(); QueueDownloadManager.notifyNewItems() }
        }
        view.findViewById<Button>(R.id.btn2).apply {
            text = "全部暂停"
            setOnClickListener { Manager.get().stopAll() }
        }
        view.findViewById<Button>(R.id.btn3).visibility = View.GONE
        view.findViewById<Button>(R.id.btn4).apply {
            text = "清空"
            setOnClickListener { Manager.get().clearAll() }
        }

        // Snapshot polling（1s）
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val snapshot = runCatching { ArrayList(Manager.get().content) }.getOrDefault(arrayListOf())
                    adapter.submit(snapshot.toList())
                    empty.visibility = if (snapshot.isEmpty()) View.VISIBLE else View.GONE
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        }

        // 监听 DOWNLOAD_ING 广播来 catch 失败状态变化（规避 polling 误差）
        val intentFilter = IntentFilter(Params.DOWNLOAD_ING)
        receiver = DownloadReceiver<Any>(
            { /* 任何变化都让下次 polling 看到 */ },
            DownloadReceiver.NOTIFY_FRAGMENT_DOWNLOADING,
        )
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(receiver!!, intentFilter)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        receiver?.let {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(it)
        }
        receiver = null
        Manager.get().clearCallback()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1000L
    }
}

private class ActiveAdapterV3 : RecyclerView.Adapter<ActiveAdapterV3.VH>() {

    private val items = mutableListOf<DownloadItem>()

    fun submit(newItems: List<DownloadItem>) {
        // 简单替换 + notifyDataSetChanged：activeList 通常很短（1-30 项）
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.cell_download_active_v3, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.taskName.text = item.name
        h.progress.progress = item.nonius
        h.percentText.text = "${item.nonius}%"

        if (item.totalSize > 0) {
            h.sizeText.text = String.format(
                "%s / %s",
                FileSizeUtil.formatFileSize(item.currentSize),
                FileSizeUtil.formatFileSize(item.totalSize)
            )
        } else {
            h.sizeText.text = "—"
        }

        if (!TextUtils.isEmpty(item.showUrl)) {
            Glide.with(h.thumb)
                .load(GlideUtil.getUrl(item.showUrl))
                .placeholder(android.R.color.transparent)
                .into(h.thumb)
        } else {
            Glide.with(h.thumb).clear(h.thumb)
            h.thumb.setImageDrawable(null)
        }

        val (label, color) = when (item.state) {
            DownloadItem.DownloadState.INIT -> "QUEUED" to "#9DA3AB"
            DownloadItem.DownloadState.DOWNLOADING -> "DOWNLOADING" to "#5EB3FF"
            DownloadItem.DownloadState.PAUSED -> "PAUSED" to "#FFB454"
            DownloadItem.DownloadState.FAILED -> "FAILED" to "#FF8B8B"
            DownloadItem.DownloadState.SUCCESS -> "DONE" to "#7CB668"
            else -> "—" to "#9DA3AB"
        }
        h.stateBadge.text = label
        h.stateBadge.setTextColor(Color.parseColor(color))

        // 暂停/继续切换 icon
        h.pauseBtn.setImageResource(
            if (item.isPaused) R.drawable.ic_baseline_play_arrow_24
            else R.drawable.ic_baseline_pause_24
        )
        h.pauseBtn.setOnClickListener {
            if (item.isPaused) Manager.get().startOne(item.uuid)
            else Manager.get().stopOne(item.uuid)
            // 立刻刷新本卡 UI（下次 polling 会再校准）
            notifyItemChanged(h.bindingAdapterPosition)
        }
        h.cancelBtn.setOnClickListener {
            Manager.get().clearOne(item.uuid)
            // 不在这里手动 remove —— polling 会同步 UI
        }

        // 同时绑定 Manager 进度回调，让"正在下载"那一项的进度条丝滑刷新
        Manager.get().setCallback(item.uuid) { progress: DownloadProgress ->
            if (Manager.get().uuid == item.uuid) {
                h.itemView.post {
                    h.progress.progress = progress.progress
                    h.percentText.text = "${progress.progress}%"
                    if (progress.totalSize > 0) {
                        h.sizeText.text = String.format(
                            "%s / %s",
                            FileSizeUtil.formatFileSize(progress.currentSize),
                            FileSizeUtil.formatFileSize(progress.totalSize)
                        )
                    }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val taskName: TextView = v.findViewById(R.id.taskName)
        val sizeText: TextView = v.findViewById(R.id.sizeText)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val stateBadge: TextView = v.findViewById(R.id.stateBadge)
        val percentText: TextView = v.findViewById(R.id.percentText)
        val pauseBtn: ImageView = v.findViewById(R.id.pauseBtn)
        val cancelBtn: ImageView = v.findViewById(R.id.cancelBtn)
    }
}
