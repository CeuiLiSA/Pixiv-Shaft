package ceui.pixiv.ui.download

import android.content.Intent
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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.Manager
import ceui.lisa.core.ManagerReactive
import ceui.lisa.core.PageData
import ceui.lisa.database.AppDatabase
import ceui.lisa.download.FileSizeUtil
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.ui.bulk.QueueDownloadManager
import ceui.pixiv.ui.bulk.UgoiraInFlight
import ceui.pixiv.ui.bulk.UgoiraPhase
import com.bumptech.glide.Glide
import com.hjq.toast.Toaster
import ceui.pixiv.witstudio.dialog.WitSkinManager
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * V3 风格 "正在下载" —— 订阅 [ManagerReactive.contentFlow]。
 *
 * 数据源是 reactive 的：Manager 任何 mutation（addTask / state 翻转 /
 * progress 1% / complete / clearAll / pumpAvailableSlots / ...）后都会
 * `invalidate()`，本 fragment 的 collector 立刻拿到当前 snapshot。
 * 完全替代旧的 1s 轮询 + DOWNLOAD_ING 广播 + tickle channel 三层架构 ——
 * 0 timer、0 broadcast。
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
    private var statusHeader: TextView? = null
    private val queueDao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }

    /**
     * 1s 采样的整体下载速度（B/s）。0 表示当前没有可观测的字节增量（空闲 / 暂停 / 全失败）。
     *
     * 由 [SpeedSampler] 每 1s 计算一次，driver coroutine 写、status 渲染 collector
     * 读，所以是 [MutableStateFlow] 而不是普通字段 —— [combine] 让"内容变更"和
     * "速度更新"任一发生时 status header 都会刷新。
     */
    private val speedBpsFlow = MutableStateFlow(0L)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_list_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        // RV 自身高度由父布局固定（fragment 内全屏 match_parent），跟 adapter 内容
        // 多少无关。setHasFixedSize(true) 跳过每次 notifyItem* 重测自身。
        list.setHasFixedSize(true)

        // 点击 row → VActivity 看一级详情。把整段 currentList 的 IllustsBean 一起
        // 拼 PageData，让用户在详情里能左右滑切到列表里相邻 item（illust 拿
        // DownloadItem.illust，ugoira 拿 UgoiraEntry.bean）。
        adapter.onItemClick = onItemClick@{ snap, all ->
            val ctx = context ?: return@onItemClick
            val clicked: IllustsBean = when (snap) {
                is ActiveSnapshot.IllustEntry -> snap.item.illust ?: run {
                    Toaster.showShort(R.string.dlmgr_queue_open_unavailable)
                    return@onItemClick
                }
                is ActiveSnapshot.UgoiraEntry -> snap.bean
            }
            val beans = all.mapNotNull { entry ->
                when (entry) {
                    is ActiveSnapshot.IllustEntry -> entry.item.illust
                    is ActiveSnapshot.UgoiraEntry -> entry.bean
                }
            }
            val index = beans.indexOfFirst { it.id == clicked.id }.coerceAtLeast(0)
            val pageData = PageData(beans)
            Container.get().addPageToMap(pageData)
            startActivity(Intent(ctx, VActivity::class.java).apply {
                putExtra(Params.POSITION, index)
                putExtra(Params.PAGE_UUID, pageData.uuid)
            })
        }
        // 完全去掉 ItemAnimator —— 试过 sample 节流 + 调 moveDuration 后仍然 choppy，
        // OnePlus / 这套 layout 上 DefaultItemAnimator 的预测式 move 动画就是不健康。
        // 直接 null 化：item 完成 → 瞬间消失；2/3 上滑 → 瞬间 snap；4 进入 → 瞬间显示。
        // 没有动画就没有"被打断的动画"。代价：完成事件不再被视觉庆祝（aria2 / Chrome
        // 下载列表也是这风格，可以接受）。进度条 / 数字 / 速度仍然实时跟手刷新。
        list.itemAnimator = null

        val empty = view.findViewById<View>(R.id.emptyState)
        view.findViewById<TextView>(R.id.emptyTitle).text = getString(R.string.dlmgr_active_empty_title)
        view.findViewById<TextView>(R.id.emptyHint).text = getString(R.string.dlmgr_active_empty_hint)

        // 顶部状态行(占用 btn3 这个空位 button 改为只读 TextView 风格)。
        // singleLine + ellipsize 兜底:btn1/btn2 已经搬去 toolbar menu 让出了大半
        // 横向空间,但极端长度的状态串 (4 counts + 速度) 仍可能挤超宽,直接末尾省略。
        statusHeader = view.findViewById<Button>(R.id.btn3).apply {
            text = "—"
            isEnabled = false
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 视觉去按钮化
            setTextColor(Color.parseColor("#7CB668"))
        }

        // btn1 (Resume) / btn2 (Pause) 已合一为 host toolbar 的 action_pause_toggle
        // (见 [DownloadManagerV3Fragment.applyMenuVisibility], pos==1 时可见)。
        // 卡片底部不再渲染这两个按钮 — 给 statusHeader (btn3) 让出宽度,
        // 「正在 N · 等待 M · 暂停 K · 失败 L · 1.2 MB/s」就不会再换行。
        // pause/resume 是 Manager + QueueDownloadManager 全局动作,host 直接调,
        // 不再绕 SharedVM,本 fragment 不需要订阅。
        view.findViewById<Button>(R.id.btn1).visibility = View.GONE
        view.findViewById<Button>(R.id.btn2).visibility = View.GONE
        val btnClear = view.findViewById<Button>(R.id.btn4).apply {
            text = getString(R.string.dlmgr_active_action_clear)
            // 联动：清空 active 同时把批量队列 DB 也清掉，避免用户清完 active 又被
            // 队列消费者重新填回去，看起来"清不掉"。
            //
            // 加确认 dialog：destructive 操作不应一键直行 —— 用户误点损失大。
            setOnClickListener {
                showClearConfirmDialog {
                    Manager.get().clearAll()
                    // 跟 QueueListV3Fragment 一致：同步取消 ugoira workers，否则
                    // 用户清完仍有 ugoira 正在跑、跑完还往用户图库写 GIF（违反"全清"）
                    runCatching { QueueDownloadManager.cancelOngoingUgoiraWorkers() }
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        runCatching { queueDao.deleteAll() }
                    }
                }
            }
        }

        // Reactive: ManagerReactive.contentFlow 在 Manager 任何 mutation 后
        // 自动推一帧（progress / state / add / remove / clearAll 全覆盖）。
        // 0 timer，0 broadcast，纯事件驱动。
        //
        // 没有 ItemAnimator 后不再需要 sample 节流（之前是为了保护动画时间窗口）。
        // conflate() 兜 backpressure：collector 慢的时候上游高频 emit 会被合并成
        // "最后一帧"，不会堆积。combine 上游的 contentFlow 本身就是 replay=1 +
        // DROP_OLDEST 的 SharedFlow，已经天然 conflate；这里再 conflate 一次双保险。
        //
        // flowOn(Default)：snapshot copy + count 计算放后台线程，UI 不挡帧。
        // combine speedBpsFlow：让"内容变更"和"速度采样"任一发生时 status header 都会刷新。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(
                    ManagerReactive.contentFlow,
                    QueueDownloadManager.ugoiraInFlightFlow,
                    speedBpsFlow,
                ) { snapshot, ugoiras, speedBps -> Triple(snapshot, ugoiras, speedBps) }
                    .conflate()
                    .flowOn(Dispatchers.Default)
                    .collect { (snapshot, ugoiras, speedBps) ->
                        val downloadingCount = snapshot.count { it.state == DownloadItem.DownloadState.DOWNLOADING }
                        val initCount = snapshot.count { it.state == DownloadItem.DownloadState.INIT }
                        val pausedCount = snapshot.count { it.state == DownloadItem.DownloadState.PAUSED }
                        val failedCount = snapshot.count { it.state == DownloadItem.DownloadState.FAILED }
                        val ugoiraCount = ugoiras.size

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

                        // 顶部状态行：N 正在 · M 等待 · ... · 1.2 MB/s
                        // 速度只在 downloadingCount > 0 且采样到非零时显示，避免暂停 / 空闲态
                        // 显示一个"陈旧"的速度值。
                        // ugoira 跟 illust 都计入"正在"——用户视角它们都在跑，分两段反而困惑；
                        // ugoira 走自己 Semaphore 不挤 illust 并发槽是实现细节，不暴露给用户。
                        val activeTotal = downloadingCount + ugoiraCount
                        val parts = buildList {
                            if (activeTotal > 0) add(getString(R.string.dlmgr_active_status_downloading_n, activeTotal))
                            if (initCount > 0) add(getString(R.string.dlmgr_active_status_waiting_n, initCount))
                            if (pausedCount > 0) add(getString(R.string.dlmgr_active_status_paused_n, pausedCount))
                            if (failedCount > 0) add(getString(R.string.dlmgr_active_status_failed_n, failedCount))
                            if (activeTotal > 0 && speedBps > 0) add(formatSpeed(speedBps))
                        }
                        statusHeader?.text = if (parts.isEmpty()) "—" else parts.joinToString(" · ")

                        adapter.submit(snapshot, ugoiras)
                        val anyWork = snapshot.isNotEmpty() || ugoiras.isNotEmpty()
                        empty.visibility = if (anyWork) View.GONE else View.VISIBLE
                        // 没有任何活跃任务时把"清空"按钮置灰,避免用户在空列表上反复点。
                        // pause/resume 改走 toolbar menu 后不在这控制 enable 状态 —
                        // 跨 fragment 同步 menu enable 不值得专开通道,允许空态点击 (no-op)。
                        btnClear.isEnabled = anyWork
                        btnClear.alpha = if (anyWork) 1f else 0.4f
                    }
            }
        }

        // 速度采样：每 1s 算一次整体网速。和上面的内容流是两条独立 coroutine，
        // 通过 [speedBpsFlow] 解耦 —— driver 写、内容流的 combine 读。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val sampler = SpeedSampler()
                while (isActive) {
                    val snapshot = Manager.get().contentSnapshot()
                    speedBpsFlow.value = sampler.sample(snapshot)
                    delay(SPEED_SAMPLE_INTERVAL_MS)
                }
            }
        }
    }

    private fun showClearConfirmDialog(onConfirm: () -> Unit) {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        WitDialog.MessageDialogBuilder(act)
            .setTitle(R.string.dlmgr_clear_active_queue_title)
            .setMessage(R.string.dlmgr_clear_active_queue_message)
            .setSkinManager(WitSkinManager.defaultInstance(act))
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.sure, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                onConfirm()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // ⚠️ 不调 Manager.clearCallback() —— 那会清掉别的页面（如 ArtworkV3Fragment）的回调。
        //    我们 setCallback 用的 key=item.uuid，新 bind 会覆盖旧的，无需主动清。
    }

    companion object {
        private const val TAG = "ActiveListV3"
        /** 整体下载速度采样间隔（毫秒）。1s 比较接近用户对"实时网速"的预期 */
        private const val SPEED_SAMPLE_INTERVAL_MS = 1000L
    }
}

/**
 * 整体下载速度采样器 —— 不修改 Manager.java 的字节计数路径，纯靠 fragment 端
 * 对 [DownloadItem.currentSize] 做差分。
 *
 * # 算法
 * 每次 [sample] 拍一张 (uuid → currentSize/totalSize) 表，跟上次对比：
 *   - 还在的 uuid：增量 = 当前 currentSize - 上次 currentSize
 *   - 上次有本次消失的 uuid：**仅当上次 currentSize 接近 totalSize**（视为自然完成）
 *     才补 totalSize - 上次 currentSize；否则视为用户 clearOne / clearAll 取消，
 *     不补差，避免取消瞬间速度虚假飙到 GB/s。
 *   - 本次新出现的 uuid：增量 = 0（首次见到，没有基线可比）
 *
 * 速度 = 总增量 / 时间差。负数截到 0。
 *
 * # 为什么不在 Manager 里加全局字节计数
 * 1. 改 Manager.java 的下载内核是高频热路径（每 ~8KB 一次），AtomicLong 虽然便宜
 *    但要新加 import + 暴露 getter，跨 Java/Kotlin 边界。
 * 2. 这里 1s 采样足够：UI 刷新本身就是 1s 量级，没必要追字节级精度。
 * 3. 跳过 cache hit / SAF skip 等无字节传输路径自然不计入 —— item 直接 currentSize=0
 *    然后 disappear，按下面的"完成阈值"判定也不会误算（currentSize=0 不达 0.95×total）。
 */
private class SpeedSampler {
    private data class Sample(val currentSize: Long, val totalSize: Long)

    private var prevByUuid: Map<String, Sample> = emptyMap()
    private var prevTimeMs: Long = 0L

    /**
     * 拍一帧并返回当前速度（B/s）。snapshot 不需要按状态过滤 —— 非
     * DOWNLOADING 的 currentSize 不会涨，差分自然 0；省一次 filter。
     */
    fun sample(snapshot: List<ceui.lisa.core.DownloadItem>): Long {
        val now = System.currentTimeMillis()
        val cur: Map<String, Sample> = snapshot.associate { it.uuid to Sample(it.currentSize, it.totalSize) }

        // 第一次采样：只记录基线，不算速度（没有 prev 时间点可比）
        if (prevTimeMs == 0L) {
            prevByUuid = cur
            prevTimeMs = now
            return 0L
        }

        var deltaBytes = 0L
        for ((uuid, s) in cur) {
            val prev = prevByUuid[uuid]
            // 新出现的 uuid：prev?.currentSize ?: 0 → 增量 = currentSize
            val inc = s.currentSize - (prev?.currentSize ?: 0L)
            if (inc > 0) deltaBytes += inc
        }
        for ((uuid, s) in prevByUuid) {
            if (cur.containsKey(uuid)) continue
            // 消失的 item —— 仅当上次进度已经过门槛（接近 totalSize）才视为自然完成
            // 补差。门槛 95% 既能覆盖 progress 回调粗粒度（每 1% 一次）让最后一档没
            // 上报满格的情况，又能把"早早就被用户 clearOne 掉"的取消挡在外面（取消
            // 通常在 currentSize 远小于 totalSize 时发生）。
            if (s.totalSize > 0 && s.currentSize >= (s.totalSize * 95L / 100L)) {
                val inc = s.totalSize - s.currentSize
                if (inc > 0) deltaBytes += inc
            }
        }

        val deltaMs = (now - prevTimeMs).coerceAtLeast(1L)
        val speedBps = (deltaBytes * 1000L / deltaMs).coerceAtLeast(0L)

        prevByUuid = cur
        prevTimeMs = now
        return speedBps
    }
}

/** 把 B/s 整数格式化成人类可读 —— 跟 [FileSizeUtil.formatFileSize] 一样的进位规则，再追加 "/s" */
private fun formatSpeed(bps: Long): String =
    if (bps <= 0) "0B/s" else "${ceui.lisa.download.FileSizeUtil.formatFileSize(bps)}/s"

/**
 * 关键陷阱：[Manager] 原地修改 [DownloadItem]（setNonius / setPaused / ...），
 * 不会创建新对象。如果 ListAdapter 直接拿 DownloadItem 做 DiffUtil 元素，旧
 * snapshot 列表和新 snapshot 列表持有**同一份对象引用**，DiffUtil 调
 * areContentsTheSame 时 a 和 b 是同一个 obj，所有字段比较恒等 → 永不发现变化
 * → 进度条视觉上冻死。这是 ListAdapter + 可变模型的经典 aliasing 陷阱。
 *
 * 修法：每次 submit 时把数据拍进 immutable snapshot 再交给 ListAdapter。data
 * class auto equals 让 DiffUtil 拿到的新旧元素之间字段比较真实反映变化。
 *
 * sealed 让一份 adapter / 一份 cell layout 同时容纳两类活跃任务：
 *   - [IllustEntry]：来自 [Manager.content] 的 page-level 图片下载（带 nonius）
 *   - [UgoiraEntry]：来自 [QueueDownloadManager.ugoiraInFlightFlow] 的 ugoira 全
 *     链路任务（无字节级 % 进度，按 phase 显示当前在哪一步）
 */
private sealed class ActiveSnapshot {
    /** DiffUtil 用的稳定标识。illust 用 uuid；ugoira 用 row.id 拼前缀避免命名空间撞 */
    abstract val key: String

    data class IllustEntry(
        /** 持有原 DownloadItem 引用，让 click handler 能拿到 uuid 给 Manager 调命令 */
        val item: DownloadItem,
        val state: Int,
        val isPaused: Boolean,
        val nonius: Int,
        val currentSize: Long,
        val totalSize: Long,
        val name: String?,
        val showUrl: String?,
    ) : ActiveSnapshot() {
        override val key: String get() = "i:" + item.uuid

        companion object {
            fun of(d: DownloadItem) = IllustEntry(
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

    data class UgoiraEntry(
        val rowId: Long,
        /** 完整 bean 而非只存 id —— row click → VActivity 需要 IllustsBean 拼 PageData */
        val bean: IllustsBean,
        val title: String,
        val showUrl: String?,
        val phase: UgoiraPhase,
    ) : ActiveSnapshot() {
        override val key: String get() = "u:" + rowId

        companion object {
            fun of(u: UgoiraInFlight) = UgoiraEntry(
                rowId = u.rowId,
                bean = u.bean,
                title = u.bean.title.orEmpty().ifEmpty { "ugoira " + u.bean.id },
                // 跟 illust 行一致:64dp 方 thumb 用 square_medium 减网络体积,
                // 缺时降级 medium → large
                showUrl = u.bean.image_urls?.let {
                    it.square_medium ?: it.medium ?: it.large
                },
                phase = u.phase,
            )
        }
    }
}

/**
 * DiffUtil 让"看起来没变"的 item 不重 bind。
 *  - 标识相同 + 内容完全一致 → 不 bind
 *  - 仅进度/状态变化 → 走 payload，仅刷新进度条/百分比/大小/徽章
 *  - 缩略图 URL 没变就根本不调 Glide.load
 */
private object ActiveDiff : DiffUtil.ItemCallback<ActiveSnapshot>() {
    override fun areItemsTheSame(a: ActiveSnapshot, b: ActiveSnapshot): Boolean = a.key == b.key
    override fun areContentsTheSame(a: ActiveSnapshot, b: ActiveSnapshot): Boolean = a == b
    override fun getChangePayload(oldItem: ActiveSnapshot, newItem: ActiveSnapshot): Any = PROGRESS_PAYLOAD
}

private const val PROGRESS_PAYLOAD = "progress"

private class ActiveAdapterV3 : ListAdapter<ActiveSnapshot, ActiveAdapterV3.VH>(ActiveDiff) {

    /**
     * 点击 row → VActivity 看一级详情。fragment 端注册，把整段 currentList 的
     * IllustsBean 一起传出去拼 PageData，让用户在详情里能左右滑切到列表里相邻的 illust。
     * 跟 [ceui.pixiv.ui.download.QueueListV3Fragment] 的行为一致。
     */
    var onItemClick: ((snap: ActiveSnapshot, all: List<ActiveSnapshot>) -> Unit)? = null

    /** ugoira 排在 illust 前面 —— 用户最近触发的批量下载里 ugoira 通常是稀缺关注点。 */
    fun submit(illusts: List<DownloadItem>, ugoiras: List<UgoiraInFlight>) {
        val combined = ArrayList<ActiveSnapshot>(illusts.size + ugoiras.size)
        ugoiras.mapTo(combined) { ActiveSnapshot.UgoiraEntry.of(it) }
        illusts.mapTo(combined) { ActiveSnapshot.IllustEntry.of(it) }
        submitList(combined)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.cell_download_active_v3, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        when (val it = getItem(pos)) {
            is ActiveSnapshot.IllustEntry -> bindFullIllust(h, it)
            is ActiveSnapshot.UgoiraEntry -> bindFullUgoira(h, it)
        }
    }

    override fun onBindViewHolder(h: VH, pos: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(h, pos)
            return
        }
        // payload-only：进度/状态/phase 变了，但缩略图、标题没变
        when (val it = getItem(pos)) {
            is ActiveSnapshot.IllustEntry -> bindIllustStateAndProgress(h, it)
            is ActiveSnapshot.UgoiraEntry -> bindUgoiraStateAndProgress(h, it)
        }
    }

    private fun bindFullIllust(h: VH, snap: ActiveSnapshot.IllustEntry) {
        h.taskName.text = snap.name
        bindIllustStateAndProgress(h, snap)
        bindThumb(h, snap.showUrl)

        // row click → VActivity（用 bindingAdapterPosition 取最新 currentList，避免
        // 闭包捕获过期的 snap.item）
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) {
                onItemClick?.invoke(getItem(p), currentList)
            }
        }

        // 暂停/继续 + 取消（每次 full bind 重设，保证 lambda 引用最新 item）
        h.pauseBtn.visibility = View.VISIBLE
        h.cancelBtn.visibility = View.VISIBLE
        h.pauseBtn.setOnClickListener {
            // 即时反馈：snapshot 列表是 immutable，无法靠 notifyItemChanged 让
            // payload-bind 看到新状态（snapshot 还是旧的）。直接操作 view —
            // 下一轮 polling 来重 snapshot 时 DiffUtil 会再 reconcile 一次。
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
            Manager.get().clearOne(snap.item.uuid)
        }
    }

    private fun bindFullUgoira(h: VH, snap: ActiveSnapshot.UgoiraEntry) {
        h.taskName.text = snap.title
        bindUgoiraStateAndProgress(h, snap)
        bindThumb(h, snap.showUrl)
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) {
                onItemClick?.invoke(getItem(p), currentList)
            }
        }
        // ugoira 没有 per-item pause/cancel —— 全局靠批量队列 tab 的暂停 / 清空。
        // 隐藏按钮避免误点；后续若要做 per-row cancel 再上。
        h.pauseBtn.visibility = View.GONE
        h.cancelBtn.visibility = View.GONE
    }

    /** 缩略图：URL 没变就不 Glide.load —— 这是消除闪烁的关键。 */
    private fun bindThumb(h: VH, showUrl: String?) {
        val newShowUrl = showUrl?.takeIf { !TextUtils.isEmpty(it) }
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
    }

    private fun bindIllustStateAndProgress(h: VH, snap: ActiveSnapshot.IllustEntry) {
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
                // totalSize=0 时（响应没 Content-Length）只显示已下载字节，让用户
                // 至少看到"在动"；否则照常 currentSize / totalSize。
                h.sizeText.text = when {
                    snap.totalSize > 0 -> String.format(
                        "%s / %s",
                        FileSizeUtil.formatFileSize(snap.currentSize),
                        FileSizeUtil.formatFileSize(snap.totalSize)
                    )
                    snap.currentSize > 0 -> String.format(
                        "%s / —",
                        FileSizeUtil.formatFileSize(snap.currentSize)
                    )
                    else -> "—"
                }
            }
            isWaiting -> h.sizeText.setText(R.string.dlmgr_active_size_waiting)
            isPaused -> h.sizeText.setText(R.string.dlmgr_active_size_paused)
            isFailed -> h.sizeText.setText(R.string.dlmgr_active_size_failed)
            else -> h.sizeText.text = "—"
        }

        // DOWNLOADING 用稍鲜亮的绿（活跃感），跟 DONE 的 #7CB668（柔和"已完成"绿）
        // 区分开 —— 都用绿但深浅有别：在跑的更亮，结束的更收。
        val (label, color) = when {
            isActive -> "DOWNLOADING" to "#4CAF50"
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

    /**
     * Ugoira 状态展示：进度条 / 百分比都隐藏，phase label 直接当 size 文字。
     *
     * 没有字节级 % 进度可报（zip 一次性下载 + 同步编码），50% 占位条视觉上像"卡死"，
     * indeterminate 又会跟 illust 行的 determinate 样式割裂；干脆都隐了，phase 文字
     * 已经能说清"在哪一步"。
     */
    private fun bindUgoiraStateAndProgress(h: VH, snap: ActiveSnapshot.UgoiraEntry) {
        h.itemView.alpha = 1.0f
        h.progress.visibility = View.GONE
        h.percentText.visibility = View.GONE

        val phaseLabel = when (snap.phase) {
            UgoiraPhase.QUEUED -> "QUEUED"
            UgoiraPhase.FETCH_META -> "FETCH META"
            UgoiraPhase.DOWNLOAD_ZIP -> "DOWNLOAD ZIP"
            UgoiraPhase.EXTRACT -> "EXTRACT"
            UgoiraPhase.INTERPOLATE -> "RIFE 2X"
            UgoiraPhase.ENCODE -> "ENCODE GIF"
        }
        h.sizeText.text = phaseLabel

        // 紫色标识 ugoira，跟绿色的图片下载 / 暂停黄 / 失败红 颜色空间错开
        h.stateBadge.text = "UGOIRA"
        h.stateBadge.setTextColor(Color.parseColor("#B388FF"))
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
