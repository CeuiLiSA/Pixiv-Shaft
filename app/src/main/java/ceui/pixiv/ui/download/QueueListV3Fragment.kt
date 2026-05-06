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
import ceui.lisa.core.Manager
import ceui.lisa.database.AppDatabase
import ceui.lisa.http.Retro
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.loxia.ObjectPool
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.DownloadQueueEntity
import ceui.pixiv.db.queue.QueueStatus
import ceui.pixiv.ui.bulk.QueueDownloadManager
import com.bumptech.glide.Glide
import com.qmuiteam.qmui.skin.QMUISkinManager
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    /** 已经成功 prefetch 过的 illustId（避免重复打 API）；fetch 失败会从这里 remove 让下次再试 */
    private val prefetchedIds = ConcurrentHashMap<Long, Boolean>()
    /** 限制同时进行的 illust 详情拉取数，避免 1000 项队列把网络打满 */
    private val prefetchSem = Semaphore(PREFETCH_MAX_CONCURRENCY)

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
        view.findViewById<TextView>(R.id.emptyTitle).text = getString(R.string.dlmgr_queue_empty_title)
        view.findViewById<TextView>(R.id.emptyHint).text = getString(R.string.dlmgr_queue_empty_hint)

        // 按钮文案根据 manager 真实状态初始化（cold-start 暂停时直接显示"继续"）
        val btnPause = view.findViewById<Button>(R.id.btn1).apply {
            text = getString(if (QueueDownloadManager.isPaused()) R.string.dlmgr_queue_action_resume else R.string.dlmgr_queue_action_pause)
        }
        val btnRetry = view.findViewById<Button>(R.id.btn2).apply { text = getString(R.string.dlmgr_queue_action_retry_failed) }
        // btn3（原"清成功记录"）已废弃 —— SUCCESS 行会自动从队列消失走到"已完成" tab，
        // 这个按钮没有实际意义。
        view.findViewById<Button>(R.id.btn3).visibility = View.GONE
        val btnClearAll = view.findViewById<Button>(R.id.btn4).apply { text = getString(R.string.dlmgr_queue_action_clear_all) }

        btnPause.setOnClickListener {
            if (QueueDownloadManager.isPaused()) {
                // 联动：批量队列恢复时，正在下载 tab 的 Manager 也跟着恢复
                QueueDownloadManager.resume()
                Manager.get().startAll()
                btnPause.text = getString(R.string.dlmgr_queue_action_pause)
            } else {
                // 联动：批量队列暂停时，连同 Manager 当前正在下的也暂停
                QueueDownloadManager.pause()
                Manager.get().stopAll()
                btnPause.text = getString(R.string.dlmgr_queue_action_resume)
            }
        }
        btnRetry.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                runCatching { dao.retryAllFailed() }
                // 用户手动点重试 → 必须 resume() 而不仅仅 tickle，否则 paused 时啥也不做
                QueueDownloadManager.resume()
            }
        }
        btnClearAll.setOnClickListener {
            // destructive 操作必须确认 —— 误点会让用户失去全部排队
            showClearConfirmDialog {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    // 联动：清空队列同时把正在下载的也一并停掉清掉，
                    // 否则用户清完队列还看到"正在下载" tab 有残留任务在跑。
                    runCatching { Manager.get().clearAll() }
                    runCatching { dao.deleteAll() }
                }
            }
        }

        // 仅 STARTED 时刷新；只显示活跃项（PENDING / DOWNLOADING / FAILED），
        // SUCCESS 完成后自动从队列视图消失，让队列真正"越来越短"。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val rows = withContext(Dispatchers.IO) {
                        runCatching { loadAllActive() }.getOrDefault(emptyList())
                    }
                    adapter.submitList(rows)
                    empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    // 空队列时禁用 暂停/继续 + 清空全部 —— 避免用户在没事可做时反复点
                    val hasWork = rows.isNotEmpty()
                    btnPause.isEnabled = hasWork
                    btnPause.alpha = if (hasWork) 1f else 0.4f
                    btnClearAll.isEnabled = hasWork
                    btnClearAll.alpha = if (hasWork) 1f else 0.4f
                    // 同步 暂停/继续 文案：用户可能从其它 tab（active）触发了 pause，
                    // 此时本 tab 的按钮文案要跟着变，否则显示"暂停"但实际已 paused 是误导。
                    btnPause.text = getString(
                        if (QueueDownloadManager.isPaused()) R.string.dlmgr_queue_action_resume
                        else R.string.dlmgr_queue_action_pause
                    )
                    // 让看得见的几条 item 把缩略图/标题加载出来：ObjectPool cache miss 的
                    // illustId 触发后台拉取，下一轮 polling DiffUtil 会自动重 bind 那些行。
                    prefetchVisibleIllusts(rows)
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * 看得见的几条 item ObjectPool cache miss → 后台拉一发 illust 详情塞回 ObjectPool。
     * 下一轮 1.5s polling DiffUtil 会因为我们没改 [DownloadQueueEntity]、不会触发 rebind，
     * 但 [QueueAdapterV3.onBindViewHolder] 内部读 [ObjectPool.getIllust] 此刻已命中，
     * 因此当 RecyclerView 自然 rebind（滚动 / 状态变化）时缩略图就出来了。
     *
     * 上限：[PREFETCH_MAX_VISIBLE] 条 / 每轮 polling，并发 [PREFETCH_MAX_CONCURRENCY]。
     * 失败的 ID 会从 [prefetchedIds] 移除让下次再试。
     */
    private fun prefetchVisibleIllusts(rows: List<DownloadQueueEntity>) {
        val missing = rows.asSequence()
            .map { it.illustId }
            .distinct()
            .filter { id ->
                prefetchedIds[id] != true && ObjectPool.getIllust(id).value == null
            }
            .take(PREFETCH_MAX_VISIBLE)
            .toList()
        if (missing.isEmpty()) return
        val scope = viewLifecycleOwner.lifecycleScope
        for (id in missing) {
            scope.launch(Dispatchers.IO) {
                prefetchSem.withPermit {
                    if (prefetchedIds.putIfAbsent(id, true) != null) return@withPermit
                    if (ObjectPool.getIllust(id).value != null) return@withPermit
                    runCatching {
                        val bean = fetchIllustOnce(id) ?: return@runCatching
                        withContext(Dispatchers.Main.immediate) {
                            runCatching { ObjectPool.updateIllust(bean) }
                            // pool 命中后 DiffUtil 不会自动 rebind（DownloadQueueEntity 没变），
                            // 主动通知该 illustId 的所有可见 row 重 bind 一次。
                            runCatching { adapter.notifyIllustChanged(id) }
                        }
                    }.onFailure {
                        prefetchedIds.remove(id)
                        Timber.tag("QueueListV3").w(it, "prefetch illust=$id failed")
                    }
                }
            }
        }
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

    private suspend fun fetchIllustOnce(id: Long): IllustsBean? = suspendCancellableCoroutine { cont ->
        val d = Retro.getAppApi().getIllustByID(id)
            .subscribeOn(Schedulers.io())
            .firstOrError()
            .subscribe(
                { resp ->
                    if (cont.isActive) cont.resume(resp.illust)
                },
                { err ->
                    if (cont.isActive) cont.resumeWithException(err)
                }
            )
        cont.invokeOnCancellation { d.dispose() }
    }

    /**
     * issue #858：旧代码直接 `pageActive(limit=200, offset=0)`，导致用户批量下载
     * 487p 暂停重启后，只显示前 200 条 —— 用户感觉"中间 300 张丢了"。实际数据
     * 都在 DB 里，consumer 会按 seq ASC 顺序消费完，只是 UI 没翻页就把后面的
     * 截掉了。
     *
     * 改成"分页拉到为止"，硬上限 [MAX_DISPLAY_ROWS] 防极端用例（5 万项时
     * RecyclerView 不至于卡死）。487 项这种正常规模一次性全显示。
     */
    private suspend fun loadAllActive(): List<DownloadQueueEntity> {
        val all = ArrayList<DownloadQueueEntity>()
        var offset = 0
        while (offset < MAX_DISPLAY_ROWS) {
            val page = dao.pageActive(limit = PAGE_SIZE, offset = offset)
            if (page.isEmpty()) break
            all.addAll(page)
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return all
    }

    companion object {
        private const val PAGE_SIZE = 200
        /**
         * UI 一次最多加载这么多条 —— 5000 条 RecyclerView 仍然流畅。
         * consumer 不受此限，会把 DB 里全部 PENDING 都消费掉，所以即使有
         * 几万条也只是前 5000 个可见，后续随消费完成自动滚出来。
         */
        private const val MAX_DISPLAY_ROWS = 5000
        private const val REFRESH_INTERVAL_MS = 1500L
        /** 每轮 polling 最多触发的 illust 详情 prefetch 数量 */
        private const val PREFETCH_MAX_VISIBLE = 30
        /** prefetch 并发上限 —— 太多会把 pixiv API 打急 */
        private const val PREFETCH_MAX_CONCURRENCY = 4
    }
}

private object QueueDiff : DiffUtil.ItemCallback<DownloadQueueEntity>() {
    override fun areItemsTheSame(a: DownloadQueueEntity, b: DownloadQueueEntity): Boolean = a.id == b.id
    override fun areContentsTheSame(a: DownloadQueueEntity, b: DownloadQueueEntity): Boolean =
        a.status == b.status && a.retryCount == b.retryCount
}

private class QueueAdapterV3 : ListAdapter<DownloadQueueEntity, QueueAdapterV3.VH>(QueueDiff) {

    /**
     * 用于 prefetch 成功后主动通知特定 illustId 的所有可见 row 重 bind。
     * DiffUtil 默认按 [DownloadQueueEntity] 内容比较，ObjectPool 填充后实体本身没变，
     * 不调这个方法的话视图就不会刷新缩略图/标题。
     */
    fun notifyIllustChanged(illustId: Long) {
        val list = currentList
        list.forEachIndexed { i, e ->
            if (e.illustId == illustId) notifyItemChanged(i)
        }
    }

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
