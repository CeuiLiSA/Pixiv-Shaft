package ceui.pixiv.ui.download

import android.graphics.Color
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.content.Intent
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.Manager
import ceui.lisa.core.PageData
import ceui.lisa.database.AppDatabase
import ceui.loxia.Illust
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.ObjectPool
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.DownloadQueueRow
import ceui.pixiv.db.queue.QueueStatus
import com.bumptech.glide.Glide
import com.hjq.toast.Toaster
import ceui.pixiv.witstudio.dialog.WitDialog
import ceui.pixiv.witstudio.dialog.WitDialogAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import ceui.loxia.appServices
import ceui.pixiv.ui.bulk.QueueDownloadManager

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

    /**
     * 在 [onAttach] 取一次而不是每次 `requireContext()`:清空/重试这类操作跑在
     * IO 协程里,阻塞的 DB 调用返回时 Fragment 可能已 detach,再 requireContext 就崩。
     * 实例是应用级的,提前拿住没有泄漏问题。
     */
    private lateinit var queueDownloadManager: QueueDownloadManager

    override fun onAttach(context: Context) {
        super.onAttach(context)
        queueDownloadManager = context.appServices().queueDownloadManager
    }

    private val dao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }
    private val sharedVm: DownloadManagerSharedViewModel by activityViewModels()
    private lateinit var adapter: QueueAdapterV3

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_list_v3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = QueueAdapterV3(viewLifecycleOwner.lifecycleScope, dao)
        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.setHasFixedSize(true)
        // 跟 ActiveListV3Fragment 一致 — 关掉 ItemAnimator。批量队列的 row 在
        // consumer pump 进度时高频 notify,默认 DefaultItemAnimator 的预测式
        // move/change 动画会让列表抖。瞬时 snap 比花哨动画对管队列场景更可读。
        list.itemAnimator = null

        // 点击 row → VActivity 看一级详情。
        //
        // 历史上这里把 currentList 全 5000 行的 illustGson 一并反序列化拼 PageData
        // 让用户能左右滑切到相邻 illust，但 5000 × 5–30KB JSON × 反序列化峰值
        // 直接把 256MB heap 撑爆。批量队列 tab 的主要用例是看进度/管队列，浏览
        // 图片是次要；现在只把点击那一张放进 PageData，单 illust 也合法。
        adapter.onItemClick = onItemClick@{ row ->
            val ctx = context ?: return@onItemClick
            // ObjectPool 命中（用户最近浏览 / consumer 处理过 / 列表懒加载灌过）→ 立即起。
            val cached = runCatching { ObjectPool.getIllust(row.illustId).value }.getOrNull()
            if (cached != null) {
                openVActivity(ctx, cached)
                return@onItemClick
            }
            // 未命中：IO 协程单行拉 illustGson 反序列化。一般不会走到 —— 用户能看到
            // 这条 row 的标题/缩略图说明已经懒加载过、ObjectPool 已经命中。
            viewLifecycleOwner.lifecycleScope.launch {
                val bean = loadIllustForRow(dao, row.id)
                if (bean == null) {
                    Toaster.showShort(R.string.dlmgr_queue_open_unavailable)
                } else {
                    runCatching { ObjectPool.updateIllust(bean) }
                    openVActivity(ctx, bean)
                }
            }
        }

        val empty = view.findViewById<View>(R.id.emptyState)
        view.findViewById<TextView>(R.id.emptyTitle).text = getString(R.string.dlmgr_queue_empty_title)
        view.findViewById<TextView>(R.id.emptyHint).text = getString(R.string.dlmgr_queue_empty_hint)

        // 文案不在这里初始化 —— pausedFlow StateFlow 一 collect 立刻 replay 当前
        // 值，下面的 combine collector 微秒级别就把 text 设上。
        val btnPause = view.findViewById<Button>(R.id.btn1)
        val btnRetry = view.findViewById<Button>(R.id.btn2).apply { text = getString(R.string.dlmgr_queue_action_retry_failed) }
        // btn3（原"清成功记录"）已废弃 —— SUCCESS 行会自动从队列消失走到"已完成" tab，
        // 这个按钮没有实际意义。
        view.findViewById<Button>(R.id.btn3).visibility = View.GONE
        val btnClearAll = view.findViewById<Button>(R.id.btn4).apply { text = getString(R.string.dlmgr_queue_action_clear_all) }

        btnPause.setOnClickListener {
            // pausedFlow 翻转后 combine collector 自动设 text，不在这里手动重复设。
            if (queueDownloadManager.isPaused()) {
                // 联动：批量队列恢复时，正在下载 tab 的 Manager 也跟着恢复
                queueDownloadManager.resume()
                Manager.get().startAll()
            } else {
                // 联动：批量队列暂停时，连同 Manager 当前正在下的也暂停
                queueDownloadManager.pause()
                Manager.get().stopAll()
            }
        }
        btnRetry.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                runCatching { dao.retryAllFailed() }
                // 用户手动点重试 → 必须 resume() 而不仅仅 tickle，否则 paused 时啥也不做
                queueDownloadManager.resume()
            }
        }
        btnClearAll.setOnClickListener {
            // destructive 操作必须确认 —— 误点会让用户失去全部排队
            showClearConfirmDialog {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    // 联动：清空队列同时把正在下载的也一并停掉清掉，
                    // 否则用户清完队列还看到"正在下载" tab 有残留任务在跑。
                    runCatching { Manager.get().clearAll() }
                    // 同步停掉 ugoira workers —— 否则用户清完，正在跑的 ugoira 还会
                    // 编完 GIF 落到用户图库（用户已表明全清，落盘等于无视意图）。
                    runCatching { queueDownloadManager.cancelOngoingUgoiraWorkers() }
                    runCatching { dao.deleteAll() }
                    queueDownloadManager.queueListInvalidations.tryEmit(Unit)
                }
            }
        }

        // 数据源 = 队列脏标记 (SharedFlow) + 暂停态 (StateFlow) 合并。
        //
        // ⚠️ 不用 Room 的 dao.flowActive：实测 Room InvalidationTracker 在
        // ~10 次/秒的连续 UPDATE 序列下首次 emit 后静默不再 re-emit
        // （用户复现：17 个 illust 标了 SUCCESS 都没让 flow 再 emit）。
        // 改成自己控制的 [QueueDownloadManager.queueListInvalidations]：consumer
        // 每次 status 变化 / LegacyBatchEnqueue 入队都 tryEmit。这边 collect
        // 后用 suspend 的 [DownloadQueueDao.pageActiveLight] 取最新行（不带
        // illustGson，避免 5000 × JSON 撑爆 heap），绕开 Room 不可靠的 Flow。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    queueDownloadManager.queueListInvalidations,
                    queueDownloadManager.pausedFlow,
                ) { _, paused -> paused }
                    .map { paused ->
                        // light projection：不拉 illustGson（5000 × 5–30KB JSON 会撑爆 heap）
                        val rows = runCatching { dao.pageActiveLight(MAX_DISPLAY_ROWS) }
                            .getOrDefault(emptyList())
                        rows to paused
                    }
                    .flowOn(Dispatchers.IO)
                    .collectLatest { (rows, paused) ->
                        Timber.tag("QueueListV3").i(
                            "[QUEUE-LIST] manual emit rows=${rows.size}"
                        )
                        adapter.submitList(rows)
                        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                        val hasWork = rows.isNotEmpty()
                        btnPause.isEnabled = hasWork
                        btnPause.alpha = if (hasWork) 1f else 0.4f
                        btnClearAll.isEnabled = hasWork
                        btnClearAll.alpha = if (hasWork) 1f else 0.4f
                        btnPause.text = getString(
                            if (paused) R.string.dlmgr_queue_action_resume
                            else R.string.dlmgr_queue_action_pause
                        )
                    }
            }
        }

        // host toolbar 的导出 menu 通过 SharedFlow 通知子 fragment；只在 pos == 0
        // 时响应（批量队列 tab）。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                sharedVm.exportRequest.collect { pos ->
                    if (pos == 0) triggerExport()
                }
            }
        }
    }

    /**
     * 批量队列 tab 的导出 — 把 active（非 SUCCESS）队列里每个 illust 展开成
     * 多张 image 的 original 直链。
     *
     * **不能**走 [DownloadQueueDao.pageActiveLight] —— light projection 没
     * illustGson，没法重建 Illust 拿到 meta_pages / meta_single_page。
     * 这里走 [DownloadQueueDao.pageActive] 拉带 illustGson 的完整 entity，
     * 反序列化后用 [originalUrlsOf] 抽 url。
     *
     * **EXPORT_HARD_CAP 较 light projection 路径下调到 5000**（跟 UI 上限
     * [MAX_DISPLAY_ROWS] 一致）—— 5000 × 5–30KB JSON × Gson.fromJson 峰值
     * 已经是 IO 线程几秒级别的开销 + 数百 MB 临时分配，再大就撑爆 heap。
     * 同一 illust 多 row 用 illustId 去重，避免多 p 重复展开。
     */
    private fun triggerExport() {
        viewLifecycleOwner.lifecycleScope.launch {
            val urls = withContext(Dispatchers.IO) {
                val rows = runCatching { dao.pageActive(EXPORT_HARD_CAP, 0) }
                    .getOrDefault(emptyList())
                val seen = HashSet<Long>()
                val out = ArrayList<String>()
                for (row in rows) {
                    if (!seen.add(row.illustId)) continue
                    val json = row.illustGson?.takeIf { it.isNotEmpty() } ?: continue
                    val illust = runCatching {
                        Shaft.sGson.fromJson(json, ceui.loxia.Illust::class.java)
                    }.getOrNull() ?: continue
                    out.addAll(originalUrlsOf(illust))
                }
                out
            }
            DownloadExportLinks.present(this@QueueListV3Fragment, urls)
        }
    }

    private fun openVActivity(ctx: android.content.Context, bean: Illust) {
        val pageData = PageData(listOf(bean))
        Container.get().addPageToMap(pageData)
        startActivity(Intent(ctx, VActivity::class.java).apply {
            putExtra(Params.POSITION, 0)
            putExtra(Params.PAGE_UUID, pageData.uuid)
        })
    }

    private fun showClearConfirmDialog(onConfirm: () -> Unit) {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        WitDialog.MessageDialogBuilder(act)
            .setTitle(R.string.dlmgr_clear_active_queue_title)
            .setMessage(R.string.dlmgr_clear_active_queue_message)
            .addAction(R.string.cancel) { d, _ -> d.dismiss() }
            .addAction(0, R.string.sure, WitDialogAction.ACTION_PROP_NEGATIVE) { d, _ ->
                d.dismiss()
                onConfirm()
            }
            .show()
    }

    companion object {
        /**
         * UI 一次最多加载这么多条 —— 5000 行 RecyclerView 仍然流畅，前提是
         * **走 [DownloadQueueDao.pageActiveLight]**（不带 illustGson 字段）。
         * 否则 5000 × 5–30KB JSON 直接把 256MB heap 钉死，引发 ListAdapter
         * currentList 上下文外的"无辜分配点" OOM（详情页 setText / 反射 / 下载
         * 字节读循环里 new byte[] 都中弹过）。
         *
         * consumer 不受此限，会把 DB 里全部 PENDING 都消费掉，所以即使有几万条
         * 也只是前 5000 个可见，后续随消费完成自动滚出来。
         *
         * 解决 issue #858（旧代码 pageActive 只拿前 200 条让用户感觉"中间丢了"）。
         */
        private const val MAX_DISPLAY_ROWS = 5000

        /**
         * 导出时一次拉的 active 队列硬上限。比 light projection（[MAX_DISPLAY_ROWS]）
         * 路径限制更紧 —— 这条路径要 select * 拿 illustGson 再 Gson.fromJson，
         * 5000 × 5–30KB × 反序列化峰值已经是几百 MB 临时分配，再大就 OOM。
         */
        private const val EXPORT_HARD_CAP = 5000
    }
}

private object QueueDiff : DiffUtil.ItemCallback<DownloadQueueRow>() {
    override fun areItemsTheSame(a: DownloadQueueRow, b: DownloadQueueRow): Boolean = a.id == b.id
    override fun areContentsTheSame(a: DownloadQueueRow, b: DownloadQueueRow): Boolean =
        a.status == b.status && a.retryCount == b.retryCount
}

/**
 * 单行懒加载：按 row.id 拉一行 entity 取 illustGson → 反序列化。IO 线程跑，
 * 可被外层协程 cancel；视口外的 VH 在 [QueueAdapterV3.onViewRecycled] 里 cancel
 * 自己的 loadJob，避免无效解析继续跑。
 */
private suspend fun loadIllustForRow(dao: DownloadQueueDao, rowId: Long): Illust? =
    withContext(Dispatchers.IO) {
        val ent = runCatching { dao.getById(rowId) }.getOrNull() ?: return@withContext null
        val json = ent.illustGson?.takeIf { it.isNotEmpty() } ?: return@withContext null
        runCatching { Shaft.sGson.fromJson(json, Illust::class.java) }.getOrNull()
    }

private class QueueAdapterV3(
    private val scope: CoroutineScope,
    private val dao: DownloadQueueDao,
) : ListAdapter<DownloadQueueRow, QueueAdapterV3.VH>(QueueDiff) {

    /** 点击 row 的回调；fragment 端按 row.id 单独反序列化 illustGson 拼 PageData */
    var onItemClick: ((row: DownloadQueueRow) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.cell_download_queue_v3, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = getItem(pos)
        // 上一次 bind 残留的懒加载先 cancel —— VH 复用时若不 cancel，旧 row 的
        // bean 解析完会写到当前 row 的 view 上（错位）。
        h.loadJob?.cancel()
        h.loadJob = null
        h.boundIllustId = item.illustId

        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) {
                onItemClick?.invoke(getItem(p))
            }
        }

        // 静态字段 —— seq / 状态徽章 / retry 跟 illust 内容无关，永远绑定。
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

        // —— illust 标题/缩略图 ——
        // 优先 ObjectPool（用户最近浏览 / consumer 已处理过 / 同 fragment 别的 row 懒加载灌过）。
        val cached = runCatching { ObjectPool.getIllust(item.illustId).value }.getOrNull()
        if (cached != null) {
            renderIllust(h, item, cached)
            return
        }

        // 杀进程重启后 ObjectPool 是空的；之前的修法在 miss 时只显示 "illust 12345"
        // 让用户看不到任何 illust 信息（回归）。改为懒加载：占位 + IO 协程拉这一行的
        // illustGson 反序列化回填 VH。视口典型只 5–10 行可见，最多 prefetch 几行 →
        // 同时刻活跃 load job 也就十来个，相比"一次反序列化 5000 行"省 100×+ 内存。
        renderPlaceholder(h, item)
        h.loadJob = scope.launch {
            val bean = loadIllustForRow(dao, item.id) ?: return@launch
            // 灌 ObjectPool —— 跟 consumer.resolveIllust 行为一致；后续别处 get
            // 同 id 直接命中，本 fragment 滚动回这条也直接走上面的 cached 分支。
            runCatching { ObjectPool.updateIllust(bean) }
            // race 防护：VH 可能已被复用 rebind 到别的行
            if (h.boundIllustId == item.illustId) {
                renderIllust(h, item, bean)
            }
        }
    }

    override fun onViewRecycled(h: VH) {
        super.onViewRecycled(h)
        // VH 滚出视口 → 取消尚未完成的懒加载，防 stale 写入
        h.loadJob?.cancel()
        h.loadJob = null
        h.boundIllustId = -1L
    }

    private fun renderIllust(h: VH, item: DownloadQueueRow, illust: Illust) {
        h.title.text = illust.title.orEmpty().ifBlank { "illustId ${item.illustId}" }
        h.author.text = illust.user?.name?.let { "by: $it" } ?: ""
        // 64dp 方 thumb 用 square_medium 减体积; square_medium 缺时 medium → large
        val showUrl = runCatching {
            illust.image_urls?.let { it.square_medium ?: it.medium ?: it.large }
        }.getOrNull()
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

    private fun renderPlaceholder(h: VH, item: DownloadQueueRow) {
        h.title.text = "illust  ${item.illustId}"
        h.author.text = ""
        Glide.with(h.thumb).clear(h.thumb)
        h.thumb.setImageDrawable(null)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val author: TextView = v.findViewById(R.id.author)
        val seqLabel: TextView = v.findViewById(R.id.seqLabel)
        val statusBadge: TextView = v.findViewById(R.id.statusBadge)
        val retryBadge: TextView = v.findViewById(R.id.retryBadge)
        /** 当前正在跑的懒加载 job；onViewRecycled / 下一次 bind 之前 cancel */
        var loadJob: Job? = null
        /** bind 时记录的 illustId；race 防护用，避免 job 完成后写到已被 rebind 的别的行 */
        var boundIllustId: Long = -1L
    }
}
