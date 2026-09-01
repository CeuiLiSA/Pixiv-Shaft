package ceui.pixiv.ui.download

import androidx.lifecycle.AndroidViewModel
import android.app.Application
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.ManagerReactive
import ceui.lisa.database.AppDatabase
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
import ceui.pixiv.services.appServices

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
class DownloadManagerSharedViewModel(app: Application) : AndroidViewModel(app) {

    private val queueDao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(app).downloadQueueDao()
    }

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
        app.appServices().queueDownloadManager.queueListInvalidations
            .map {
                QueueCounts(
                    pending = runCatching { queueDao.countByStatus(QueueStatus.PENDING) }.getOrDefault(0),
                    downloading = runCatching { queueDao.countByStatus(QueueStatus.DOWNLOADING) }.getOrDefault(0),
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
     * 已完成 tab 标题末尾的数字 —— 由 [DoneListV3Fragment] 分组聚合完成后回填
     * (groups.size = 实际展示的卡片数)。
     *
     * 历史上从 download_queue.SUCCESS 行派生,但那张表跟列表数据源
     * illust_download_table 不是一张表。Auto Backup 部分还原 / 用户历史上
     * 分别清理过其中一张表等场景下,两边会错位 —— 用户会看到 "已完成 1944"
     * 但列表却是空的(issue: 卸载重装后 1944/空列表)。改为列表自己回填后,数字
     * 永远等于实际可见卡片数,从数据源上消除不一致。
     */
    private val _doneCardCount = MutableStateFlow(0)
    val doneCardCount: StateFlow<Int> get() = _doneCardCount

    fun publishDoneCardCount(count: Int) {
        _doneCardCount.value = count
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
