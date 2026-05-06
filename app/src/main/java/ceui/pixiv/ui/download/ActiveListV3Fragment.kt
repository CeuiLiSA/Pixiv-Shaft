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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.Manager
import ceui.lisa.database.AppDatabase
import ceui.lisa.download.FileSizeUtil
import ceui.lisa.notification.DownloadReceiver
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.ui.bulk.QueueDownloadManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val queueDao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }

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
        view.findViewById<TextView>(R.id.emptyTitle).text = getString(R.string.dlmgr_active_empty_title)
        view.findViewById<TextView>(R.id.emptyHint).text = getString(R.string.dlmgr_active_empty_hint)

        // 顶部状态行（占用 btn3 这个空位 button 改为只读 TextView 风格）
        statusHeader = view.findViewById<Button>(R.id.btn3).apply {
            text = "—"
            isEnabled = false
            // 视觉去按钮化
            setTextColor(Color.parseColor("#7CB668"))
        }

        // 操作 bar
        val btnResume = view.findViewById<Button>(R.id.btn1).apply {
            text = getString(R.string.dlmgr_active_action_resume_all)
            // 联动：恢复 active 同时恢复批量队列
            setOnClickListener { Manager.get().startAll(); QueueDownloadManager.resume() }
        }
        val btnPause = view.findViewById<Button>(R.id.btn2).apply {
            text = getString(R.string.dlmgr_active_action_pause_all)
            // 联动：暂停 active 同时暂停批量队列消费者
            setOnClickListener { Manager.get().stopAll(); QueueDownloadManager.pause() }
        }
        val btnClear = view.findViewById<Button>(R.id.btn4).apply {
            text = getString(R.string.dlmgr_active_action_clear)
            // 联动：清空 active 同时把批量队列 DB 也清掉，避免用户清完 active 又被
            // 队列消费者重新填回去，看起来"清不掉"。
            setOnClickListener {
                Manager.get().clearAll()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    runCatching { queueDao.deleteAll() }
                }
            }
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
                        if (downloadingCount > 0) add(getString(R.string.dlmgr_active_status_downloading_n, downloadingCount))
                        if (initCount > 0) add(getString(R.string.dlmgr_active_status_waiting_n, initCount))
                        if (pausedCount > 0) add(getString(R.string.dlmgr_active_status_paused_n, pausedCount))
                        if (failedCount > 0) add(getString(R.string.dlmgr_active_status_failed_n, failedCount))
                    }
                    statusHeader?.text = if (parts.isEmpty()) "—" else parts.joinToString(" · ")

                    adapter.submit(snapshot.toList())
                    empty.visibility = if (snapshot.isEmpty()) View.VISIBLE else View.GONE
                    // 没有任何活跃任务时把操作按钮置灰，避免用户在空列表上反复点
                    val hasWork = snapshot.isNotEmpty()
                    btnResume.isEnabled = hasWork
                    btnResume.alpha = if (hasWork) 1f else 0.4f
                    btnPause.isEnabled = hasWork
                    btnPause.alpha = if (hasWork) 1f else 0.4f
                    btnClear.isEnabled = hasWork
                    btnClear.alpha = if (hasWork) 1f else 0.4f
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

/**
 * DiffUtil 让"看起来没变"的 item 不重 bind。issue: 1s polling 用
 * notifyDataSetChanged() 全量重 bind，每次都重 Glide.load 缩略图 → 视觉上闪烁
 * （即使在暂停态也闪，因为 polling 不区分状态）。
 *
 * 行为：
 *   - 标识相同（uuid）+ 内容完全一致 → 不 bind
 *   - 仅进度/状态变化 → 走 payload，仅刷新进度条/百分比/大小/徽章
 *   - 缩略图 URL 没变就根本不调 Glide.load
 */
private object ActiveDiff : DiffUtil.ItemCallback<DownloadItem>() {
    override fun areItemsTheSame(a: DownloadItem, b: DownloadItem): Boolean = a.uuid == b.uuid
    override fun areContentsTheSame(a: DownloadItem, b: DownloadItem): Boolean =
        a.state == b.state &&
            a.isPaused == b.isPaused &&
            a.nonius == b.nonius &&
            a.currentSize == b.currentSize &&
            a.totalSize == b.totalSize &&
            a.name == b.name &&
            a.showUrl == b.showUrl
    override fun getChangePayload(oldItem: DownloadItem, newItem: DownloadItem): Any = PROGRESS_PAYLOAD
}

private const val PROGRESS_PAYLOAD = "progress"

private class ActiveAdapterV3 : ListAdapter<DownloadItem, ActiveAdapterV3.VH>(ActiveDiff) {

    fun submit(newItems: List<DownloadItem>) {
        // ListAdapter.submitList copies + diff —— 引用比较失败也会进 DiffUtil
        submitList(ArrayList(newItems))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.cell_download_active_v3, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        bindFull(h, getItem(pos))
    }

    override fun onBindViewHolder(h: VH, pos: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            bindFull(h, getItem(pos))
        } else {
            // payload-only：进度/状态变了，但缩略图、文件名没变
            bindStateAndProgress(h, getItem(pos))
        }
    }

    private fun bindFull(h: VH, item: DownloadItem) {
        h.taskName.text = item.name
        bindStateAndProgress(h, item)

        // 缩略图：原始 showUrl 没变就不 Glide.load —— 这是消除闪烁的关键。
        // 用原始 String 比较（GlideUrl 没实现稳定的 equals，比较起来不靠谱）
        val newShowUrl = item.showUrl?.takeIf { !TextUtils.isEmpty(it) }
        if (newShowUrl != h.lastLoadedUrl) {
            if (!newShowUrl.isNullOrEmpty()) {
                Glide.with(h.thumb)
                    .load(GlideUtil.getUrl(newShowUrl))
                    .placeholder(android.R.color.transparent)
                    .into(h.thumb)
            } else {
                Glide.with(h.thumb).clear(h.thumb)
                h.thumb.setImageDrawable(null)
            }
            h.lastLoadedUrl = newShowUrl
        }

        // 暂停/继续 + 取消（每次 full bind 重设，保证 lambda 引用最新 item）
        h.pauseBtn.setOnClickListener {
            if (item.isPaused) Manager.get().startOne(item.uuid)
            else Manager.get().stopOne(item.uuid)
            // 状态变化通过下一轮 polling + DiffUtil 推 payload 即可，不再 notifyItemChanged
        }
        h.cancelBtn.setOnClickListener {
            Manager.get().clearOne(item.uuid)
        }

        // 故意不再 Manager.get().setCallback(item.uuid) { ... } —— 之前每次 bind 都
        // 注册一个 lambda，Manager.mCallback HashMap 按 uuid 存且永远不清，长跑下
        // 100000+ 闭包会持有 ViewHolder 引用 → 内存堆积 OOM。
        // 进度更新依赖 1s polling + DiffUtil payload 触发 bindStateAndProgress。
    }

    private fun bindStateAndProgress(h: VH, item: DownloadItem) {
        // —— 状态分类决定视觉权重 ——
        val isActive = item.state == DownloadItem.DownloadState.DOWNLOADING
        val isWaiting = item.state == DownloadItem.DownloadState.INIT
        val isPaused = item.isPaused || item.state == DownloadItem.DownloadState.PAUSED
        val isFailed = item.state == DownloadItem.DownloadState.FAILED

        h.itemView.alpha = if (isActive || isFailed) 1.0f else 0.55f

        h.progress.visibility = if (isActive) View.VISIBLE else View.GONE
        h.percentText.visibility = if (isActive) View.VISIBLE else View.GONE
        if (isActive) {
            h.progress.progress = item.nonius
            h.percentText.text = "${item.nonius}%"
        }

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
            isWaiting -> h.sizeText.setText(R.string.dlmgr_active_size_waiting)
            isPaused -> h.sizeText.setText(R.string.dlmgr_active_size_paused)
            isFailed -> h.sizeText.setText(R.string.dlmgr_active_size_failed)
            else -> h.sizeText.text = "—"
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

        h.pauseBtn.setImageResource(
            if (item.isPaused) R.drawable.ic_baseline_play_arrow_24
            else R.drawable.ic_baseline_pause_24
        )
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val taskName: TextView = v.findViewById(R.id.taskName)
        val sizeText: TextView = v.findViewById(R.id.sizeText)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val stateBadge: TextView = v.findViewById(R.id.stateBadge)
        val percentText: TextView = v.findViewById(R.id.percentText)
        val pauseBtn: ImageView = v.findViewById(R.id.pauseBtn)
        val cancelBtn: ImageView = v.findViewById(R.id.cancelBtn)
        /** 上次 Glide.load 的 URL（含 referer 拼接后）；URL 不变就跳过加载，消除闪烁 */
        var lastLoadedUrl: String? = null
    }
}
