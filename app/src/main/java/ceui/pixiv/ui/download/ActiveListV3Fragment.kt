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
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * V3 风格 "正在下载" — 监听 Manager.content。
 *
 * 并发下载（Settings.maxConcurrentDownloads，默认 1，上限 5）：
 *   - 任意时刻 DOWNLOADING 数量 ≤ 用户配置的并发数
 *   - 其余可下载的 page 处于 INIT（等待）
 *   - DOWNLOADING 卡：完整不透明 + 蓝色进度条 + 实时大小/百分比
 *   - INIT 卡：半透明 0.55 + 隐藏进度条/大小 + 文字 "等待中…"
 *   - 顶部状态行明确写 "N 正在 · M 等待"
 *   - 运行时 invariant：snapshot 里 DOWNLOADING > 配置上限 直接 warn 到日志
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
            //
            // 加确认 dialog：destructive 操作不应一键直行 —— 用户误点损失大。
            setOnClickListener {
                showClearConfirmDialog {
                    Manager.get().clearAll()
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { queueDao.deleteAll() }
                    }
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

                    // 运行时不变量：DOWNLOADING 数量永远不应该超过绝对上限 5。
                    // 注意不要拿当前 maxConcurrent 比 —— 用户从 5 调到 2 后那 5 条
                    // 在传的不会立即被掐，会自然消化，期间 downloadingCount > 2
                    // 是正常现象，不能误报。
                    if (downloadingCount > 5) {
                        Timber.tag(TAG).w(
                            "INVARIANT: ${downloadingCount} DOWNLOADING > absolute max 5! " +
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

    private fun showClearConfirmDialog(onConfirm: () -> Unit) {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        QMUIDialog.MessageDialogBuilder(act)
            .setTitle(R.string.dlmgr_clear_active_queue_title)
            .setMessage(R.string.dlmgr_clear_active_queue_message)
            .setSkinManager(QMUISkinManager.defaultInstance(act))
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.sure, QMUIDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                onConfirm()
            }
            .show()
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
 * 关键陷阱：[Manager] 原地修改 [DownloadItem]（setNonius / setPaused / ...），
 * 不会创建新对象。如果 ListAdapter 直接拿 DownloadItem 做 DiffUtil 元素，旧
 * snapshot 列表和新 snapshot 列表持有**同一份对象引用**，DiffUtil 调
 * areContentsTheSame 时 a 和 b 是同一个 obj，所有字段比较恒等 → 永不发现变化
 * → 进度条视觉上冻死。这是 ListAdapter + 可变模型的经典 aliasing 陷阱。
 *
 * 修法：每次 submit 时把 DownloadItem 的"渲染相关字段"拍进一个 immutable
 * data class [ActiveSnapshot]。新旧 ActiveSnapshot 之间字段比较真实反映变化；
 * data class auto equals 让 DiffUtil 干净工作。
 */
private data class ActiveSnapshot(
    /** 持有原 DownloadItem 引用，让 click handler 能拿到 uuid 给 Manager 调命令 */
    val item: DownloadItem,
    val state: Int,
    val isPaused: Boolean,
    val nonius: Int,
    val currentSize: Long,
    val totalSize: Long,
    val name: String?,
    val showUrl: String?,
) {
    companion object {
        fun of(d: DownloadItem) = ActiveSnapshot(
            item = d,
            state = d.state,
            isPaused = d.isPaused,
            nonius = d.nonius,
            currentSize = d.currentSize,
            totalSize = d.totalSize,
            name = d.name,
            showUrl = d.showUrl,
        )
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
private object ActiveDiff : DiffUtil.ItemCallback<ActiveSnapshot>() {
    override fun areItemsTheSame(a: ActiveSnapshot, b: ActiveSnapshot): Boolean =
        a.item.uuid == b.item.uuid
    override fun areContentsTheSame(a: ActiveSnapshot, b: ActiveSnapshot): Boolean = a == b
    override fun getChangePayload(oldItem: ActiveSnapshot, newItem: ActiveSnapshot): Any = PROGRESS_PAYLOAD
}

private const val PROGRESS_PAYLOAD = "progress"

private class ActiveAdapterV3 : ListAdapter<ActiveSnapshot, ActiveAdapterV3.VH>(ActiveDiff) {

    fun submit(newItems: List<DownloadItem>) {
        // 把可变 DownloadItem 拍成 immutable snapshot 后再交给 ListAdapter，
        // 让 DiffUtil 拿到的新旧元素是不同对象、字段比较真实反映变化。
        submitList(newItems.map { ActiveSnapshot.of(it) })
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

    private fun bindFull(h: VH, snap: ActiveSnapshot) {
        h.taskName.text = snap.name
        bindStateAndProgress(h, snap)

        // 缩略图：原始 showUrl 没变就不 Glide.load —— 这是消除闪烁的关键。
        // 用原始 String 比较（GlideUrl 没实现稳定的 equals，比较起来不靠谱）
        val newShowUrl = snap.showUrl?.takeIf { !TextUtils.isEmpty(it) }
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
            // 即时反馈：snapshot 列表是 immutable，无法靠 notifyItemChanged 让
            // payload-bind 看到新状态（snapshot 还是旧的）。直接操作 view —
            // 下一轮 1s polling 来重 snapshot 时 DiffUtil 会再 reconcile 一次。
            val live = snap.item
            val wasPaused = live.isPaused
            if (wasPaused) Manager.get().startOne(live.uuid)
            else Manager.get().stopOne(live.uuid)
            h.pauseBtn.setImageResource(
                if (wasPaused) R.drawable.ic_baseline_pause_24
                else R.drawable.ic_baseline_play_arrow_24
            )
        }
        h.cancelBtn.setOnClickListener {
            // Manager.clearOne 会从 content 移除该 item，下一轮 polling 通过
            // DiffUtil 自动消失，不需要手动通知。
            Manager.get().clearOne(snap.item.uuid)
        }

        // 故意不再 Manager.get().setCallback(item.uuid) { ... } —— 之前每次 bind 都
        // 注册一个 lambda，Manager.mCallback HashMap 按 uuid 存且永远不清，长跑下
        // 100000+ 闭包会持有 ViewHolder 引用 → 内存堆积 OOM。
        // 进度更新依赖 1s polling + DiffUtil payload 触发 bindStateAndProgress。
    }

    private fun bindStateAndProgress(h: VH, snap: ActiveSnapshot) {
        // —— 状态分类决定视觉权重 ——
        val isActive = snap.state == DownloadItem.DownloadState.DOWNLOADING
        val isWaiting = snap.state == DownloadItem.DownloadState.INIT
        val isPaused = snap.isPaused || snap.state == DownloadItem.DownloadState.PAUSED
        val isFailed = snap.state == DownloadItem.DownloadState.FAILED

        h.itemView.alpha = if (isActive || isFailed) 1.0f else 0.55f

        h.progress.visibility = if (isActive) View.VISIBLE else View.GONE
        h.percentText.visibility = if (isActive) View.VISIBLE else View.GONE
        if (isActive) {
            h.progress.progress = snap.nonius
            h.percentText.text = "${snap.nonius}%"
        }

        when {
            isActive -> {
                h.sizeText.text = if (snap.totalSize > 0) {
                    String.format(
                        "%s / %s",
                        FileSizeUtil.formatFileSize(snap.currentSize),
                        FileSizeUtil.formatFileSize(snap.totalSize)
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
            snap.state == DownloadItem.DownloadState.SUCCESS -> "DONE" to "#7CB668"
            else -> "—" to "#9DA3AB"
        }
        h.stateBadge.text = label
        h.stateBadge.setTextColor(Color.parseColor(color))

        h.pauseBtn.setImageResource(
            if (snap.isPaused) R.drawable.ic_baseline_play_arrow_24
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
