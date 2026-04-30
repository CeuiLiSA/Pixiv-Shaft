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
import timber.log.Timber

/**
 * V3 风格 "正在下载" — 监听 Manager.content。
 *
 * 关于"看似多个并发下载"的澄清：
 *   一个 N-page illust 进入下载时，[Manager.content] 会同时挂 N 个 DownloadItem，
 *   但 [Manager.loop] 严格串行下载（每完成一个才启下一个）。任意时刻 **只有 1 个**
 *   item 处于 DOWNLOADING，其余都是 INIT（等待）。本 UI 用以下方式让区分一目了然：
 *
 *     - 顶部统计行明确写 "1 正在 · N 等待"
 *     - DOWNLOADING 卡：完整不透明 + 蓝色进度条 + 实时大小/百分比
 *     - INIT 卡：半透明 0.55 + 隐藏进度条/大小 + 文字 "等待中…"
 *     - 运行时 invariant：snapshot 里 DOWNLOADING > 1 直接 warn 到日志
 */
class ActiveListV3Fragment : Fragment() {

    private val adapter = ActiveAdapterV3()
    private var receiver: DownloadReceiver<*>? = null
    private var statusHeader: TextView? = null

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
        view.findViewById<TextView>(R.id.emptyHint).text =
            "队列里的任务会自动转到这里\n显示 page 级实时进度"

        // 顶部状态行（占用 btn3 这个空位 button 改为只读 TextView 风格）
        statusHeader = view.findViewById<Button>(R.id.btn3).apply {
            text = "—"
            isEnabled = false
            // 视觉去按钮化
            setTextColor(Color.parseColor("#7CB668"))
        }

        // 操作 bar
        view.findViewById<Button>(R.id.btn1).apply {
            text = "全部继续"
            setOnClickListener { Manager.get().startAll(); QueueDownloadManager.notifyNewItems() }
        }
        view.findViewById<Button>(R.id.btn2).apply {
            text = "全部暂停"
            setOnClickListener { Manager.get().stopAll() }
        }
        view.findViewById<Button>(R.id.btn4).apply {
            text = "清空"
            setOnClickListener { Manager.get().clearAll() }
        }

        // Snapshot polling（1s）
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val snapshot = runCatching {
                        ArrayList(Manager.get().content)
                    }.getOrDefault(arrayListOf())

                    val downloadingCount = snapshot.count {
                        it.state == DownloadItem.DownloadState.DOWNLOADING
                    }
                    val initCount = snapshot.count {
                        it.state == DownloadItem.DownloadState.INIT
                    }
                    val pausedCount = snapshot.count {
                        it.state == DownloadItem.DownloadState.PAUSED
                    }
                    val failedCount = snapshot.count {
                        it.state == DownloadItem.DownloadState.FAILED
                    }

                    // 运行时不变量：DOWNLOADING 应当永远 <= 1（Manager.loop 串行）
                    if (downloadingCount > 1) {
                        Timber.tag(TAG).w(
                            "INVARIANT: ${downloadingCount} items in DOWNLOADING state simultaneously! " +
                                snapshot.filter { it.state == DownloadItem.DownloadState.DOWNLOADING }
                                    .joinToString { "${it.uuid}/${it.illust?.id}" }
                        )
                    }

                    // 顶部状态行
                    val parts = buildList {
                        if (downloadingCount > 0) add("$downloadingCount 正在下载")
                        if (initCount > 0) add("$initCount 等待中")
                        if (pausedCount > 0) add("$pausedCount 已暂停")
                        if (failedCount > 0) add("$failedCount 失败")
                    }
                    statusHeader?.text = if (parts.isEmpty()) "—" else parts.joinToString(" · ")

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
        // ⚠️ 不调 Manager.clearCallback() —— 那会清掉别的页面（如 ArtworkV3Fragment）的回调。
        //    我们 setCallback 用的 key=item.uuid，新 bind 会覆盖旧的，无需主动清。
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1000L
        private const val TAG = "ActiveListV3"
    }
}

private class ActiveAdapterV3 : RecyclerView.Adapter<ActiveAdapterV3.VH>() {

    private val items = mutableListOf<DownloadItem>()

    fun submit(newItems: List<DownloadItem>) {
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

        // —— 状态分类决定视觉权重 ——
        val isActive = item.state == DownloadItem.DownloadState.DOWNLOADING
        val isWaiting = item.state == DownloadItem.DownloadState.INIT
        val isPaused = item.isPaused || item.state == DownloadItem.DownloadState.PAUSED
        val isFailed = item.state == DownloadItem.DownloadState.FAILED

        // 等待中的卡片显著弱化
        h.itemView.alpha = if (isActive || isFailed) 1.0f else 0.55f

        // 进度条：只对 DOWNLOADING 显示
        h.progress.visibility = if (isActive) View.VISIBLE else View.GONE
        h.percentText.visibility = if (isActive) View.VISIBLE else View.GONE
        if (isActive) {
            h.progress.progress = item.nonius
            h.percentText.text = "${item.nonius}%"
        }

        // size 文本：DOWNLOADING 显示实际大小；其它用人话替代
        when {
            isActive -> {
                h.sizeText.text = if (item.totalSize > 0) {
                    String.format(
                        "%s / %s",
                        FileSizeUtil.formatFileSize(item.currentSize),
                        FileSizeUtil.formatFileSize(item.totalSize)
                    )
                } else "—"
            }
            isWaiting -> h.sizeText.text = "等待中…"
            isPaused -> h.sizeText.text = "已暂停"
            isFailed -> h.sizeText.text = "下载失败"
            else -> h.sizeText.text = "—"
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

        val (label, color) = when {
            isActive -> "DOWNLOADING" to "#5EB3FF"
            isPaused -> "PAUSED" to "#FFB454"
            isFailed -> "FAILED" to "#FF8B8B"
            isWaiting -> "QUEUED" to "#9DA3AB"
            item.state == DownloadItem.DownloadState.SUCCESS -> "DONE" to "#7CB668"
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
            notifyItemChanged(h.bindingAdapterPosition)
        }
        h.cancelBtn.setOnClickListener {
            Manager.get().clearOne(item.uuid)
        }

        // 进度回调只对当前活动项有意义
        if (isActive) {
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
