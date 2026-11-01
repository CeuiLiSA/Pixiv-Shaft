package ceui.pixiv.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.core.DoneCardCounter
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.DownloadDao
import ceui.lisa.utils.Params
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.QueueStatus
import ceui.pixiv.ui.bulk.QueueDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 三个 tab 共享的 stats 数据源。
 *
 * 数据源：
 *   - 批量队列 PENDING / DOWNLOADING 计数从 [QueueDownloadManager.queueListInvalidations]
 *     脏标记 + suspend [DownloadQueueDao.countByStatus] 派生。**不用** Room 的
 *     `flowCountByStatus`：实测 Room InvalidationTracker 在快速连续 UPDATE
 *     序列下首次 emit 后静默不再 re-emit（用户已复现，17 个 SUCCESS 一次都
 *     没让 Flow 再 emit）。改成自己 tick 后 suspend 查一次，可靠。
 *   - 当前活跃 page 数从 [ManagerReactive.contentFlow] 派生。
 *   - 已完成 tab 数字走 [doneCardCount]，由 [DoneListV3Fragment] 回填（不再
 *     由本 VM 派生）—— 历史上从 download_queue.SUCCESS 派生，跟列表数据源
 *     illust_download_table 是两张表，会出现 "1944 / 空列表" 的错位。
 */
@HiltViewModel
class DownloadManagerSharedViewModel @Inject constructor(
    queueDownloadManager: QueueDownloadManager,
    // Lazy：首次计数查询（已在 IO 上）才去拿库，VM 构造仍不碰 Room。
    private val queueDao: Lazy<DownloadQueueDao>,
    /** 同理 Lazy：只有 [refreshDoneCardCount] 才去拿库。 */
    private val downloadDao: Lazy<DownloadDao>,
) : ViewModel() {

    data class Snapshot(
        val queuePending: Int,
        val queueDownloading: Int,
        val activeCount: Int,
    )

    /**
     * 从 ManagerReactive 派生的活跃 page 数。distinctUntilChanged 避免高频
     * progress invalidate 让上层 combine 频繁重算，无谓 setText。
     */
    private val activePageCountFlow: Flow<Int> =
        ManagerReactive.contentFlow
            .map { content -> content.count { it.state == DownloadItem.DownloadState.DOWNLOADING } }
            .distinctUntilChanged()

    /**
     * 队列计数 Flow：每次 [QueueDownloadManager.queueListInvalidations] tick
     * 就 suspend 查 PENDING / DOWNLOADING 两个状态的 count（host 只用这两个，
     * SUCCESS / FAILED 不再读了）。queueListInvalidations 本身是 SharedFlow(replay=1)，
     * 新订阅立刻拿一次初始 tick。
     */
    private val queueCountsFlow: Flow<QueueCounts> =
        queueDownloadManager.queueListInvalidations
            .map {
                QueueCounts(
                    pending = runCatching { queueDao.get().countByStatus(QueueStatus.PENDING) }.getOrDefault(0),
                    downloading = runCatching { queueDao.get().countByStatus(QueueStatus.DOWNLOADING) }.getOrDefault(0),
                )
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    private data class QueueCounts(val pending: Int, val downloading: Int)

    /**
     * 把队列计数 + active count 合并成 Snapshot。
     * combine 等所有 upstream 至少各 emit 一次 —— SharedFlow / StateFlow 都
     * 自带初始值，几乎立刻 first emit。
     */
    fun snapshots(): Flow<Snapshot> = combine(
        queueCountsFlow,
        activePageCountFlow,
    ) { counts, activeCount ->
        Snapshot(
            queuePending = counts.pending,
            queueDownloading = counts.downloading,
            activeCount = activeCount,
        )
    }

    /**
     * 导出按钮的事件通道：host ([DownloadManagerV3Fragment]) 点击 toolbar 上的
     * 导出 menu 时 emit 当前 tab pos，子 fragment ([QueueListV3Fragment]
     * pos==0 / [DoneListV3Fragment] pos==2) collect 后各自触发自己的导出流程。
     *
     * replay=0：按钮事件不需要 replay，否则 fragment STARTED 时会重放历史点击。
     * extraBufferCapacity=1：tryEmit 不会丢，host 的点击都能落到唯一一个监听
     * 的 fragment（同一 tab 的 fragment 每次 onResume 才会 collect 起来）。
     */
    private val _exportRequest = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 1)
    val exportRequest: SharedFlow<Int> = _exportRequest.asSharedFlow()

    fun requestExport(tabPos: Int) {
        _exportRequest.tryEmit(tabPos)
    }

    /**
     * 已完成 tab 标题末尾的数字 —— 数字 = 前 600 行折成的卡片数，跟列表同源同口径。
     *
     * 历史上从 download_queue.SUCCESS 行派生，但那张表跟列表数据源
     * illust_download_table 不是一张表。Auto Backup 部分还原 / 用户历史上分别清理过
     * 其中一张表等场景下，两边会错位 —— 用户会看到 "已完成 1944" 但列表却是空的
     * (issue: 卸载重装后 1944/空列表)。改为跟列表同源后，数字永远等于实际可见卡片数。
     *
     * 现在由 [DoneCardCounter] 持有：列表只在已完成 tab 可见时才跑（RESUMED），离开 tab
     * 后靠写库时的增量维持，不再要求列表一直重算。口径与「窗口满后冻结」的原因见该类注释。
     */
    val doneCardCount: StateFlow<Int> get() = DoneCardCounter.cards

    /**
     * 列表算完一组就整组覆盖（对账）。[rowCount] 是这次加载到的行数，用来判断 600 行窗口
     * 满没满 —— 满了以后增量会冻结（原因见 [DoneCardCounter]）。
     *
     * 搜索态不要走这里 —— 那时行数只是命中行数，拿它更新「窗口满没满」会把行数改小，
     * 增量就会误以为窗口还有空位而继续 +1。搜索态用 [publishDoneCardCount]。
     */
    fun reconcileDoneCardCount(cards: Int, rowCount: Int) {
        DoneCardCounter.reconcile(cards, rowCount)
    }

    /** 只改数字、不动行数：搜索态（数字是命中数）和删一条后的乐观更新都走这里。 */
    fun publishDoneCardCount(count: Int) {
        DoneCardCounter.set(count)
    }

    /**
     * 进下载管理页时对一次账。
     *
     * 已完成 tab 是 RESUMED 才跑列表，用户可能压根没点进去，那就没人算数字了；这里用一条
     * 只读 fileName + illustId 的查询（[DownloadDao.countDoneCardsInWindow]）补上，不搬
     * illustGson blob。数字平时的更新靠 [DoneCardCounter.onRowInserted] 的增量，这里只是
     * 给增量提供基准。
     *
     * **只在 [DoneCardCounter.needsReconcile] 为 true 时查**：那条查询要全表扫描 + 排序，
     * 3GB / 5 万行实测约 1s（同条件的 `SELECT *` 是 13s，省掉的是 blob 那部分）。
     * 每次进页面都跑是不可接受的，而 #1288 的 downloadTime 索引合入后它会变成索引 seek，
     * 那时可以考虑放宽这个闸。
     *
     * 顺带兜住两类**不经过 insertDownload、因此没有增量**的写库：设置页的
     * 「清除批量下载关联数据」和批量导入。它们会 markStale，下次进页面时在这里纠正。
     */
    fun refreshDoneCardCount() {
        // 搜索态下 tab 数字是命中数，这时候对账会把它覆盖成全库数，跳过。
        if (doneSearchQuery.value != null) return
        if (!DoneCardCounter.needsReconcile()) return
        viewModelScope.launch(Dispatchers.IO) {
            val dao = runCatching { downloadDao.get() }.getOrNull() ?: return@launch
            val total = runCatching { dao.countAll() }.getOrDefault(0)
            val cards = if (total == 0) 0 else runCatching {
                dao.countDoneCardsInWindow(Params.NOVEL_KEY, DoneCardCounter.WINDOW)
            }.getOrDefault(0)
            DoneCardCounter.reconcile(cards, total)
        }
    }

    /**
     * 已完成 tab 的搜索 query。host toolbar 的 SearchView 输入 → 写入；
     * [DoneListV3Fragment] collect 后切换数据源 (LIKE query vs invalidations
     * 默认列表)。null = 未进入搜索；空串 = SearchView 展开但还没输；非空 =
     * 真实查询词。
     */
    private val _doneSearchQuery = MutableStateFlow<String?>(null)
    val doneSearchQuery: StateFlow<String?> get() = _doneSearchQuery

    fun setDoneSearchQuery(q: String?) {
        _doneSearchQuery.value = q
    }
}
