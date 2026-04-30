package ceui.pixiv.ui.download

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import ceui.lisa.activities.Shaft
import ceui.lisa.core.DownloadItem
import ceui.lisa.core.Manager
import ceui.lisa.database.AppDatabase
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.QueueStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay

/**
 * 三个 tab 共享的 stats 数据源；统一在 IO 线程查 DAO 和 Manager.content。
 */
class DownloadManagerSharedViewModel(app: Application) : AndroidViewModel(app) {

    private val queueDao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }
    private val downloadDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadDao()
    }

    data class Snapshot(
        val queuePending: Int,
        val queueDownloading: Int,
        val queueSuccess: Int,
        val queueFailed: Int,
        val activeCount: Int,
    )

    /**
     * 每 [REFRESH_INTERVAL_MS] 一次的轮询 flow；IO 线程查询，UI 收集。
     * Cold flow —— 配合 lifecycleScope.repeatOnLifecycle(STARTED) 自动停跑。
     */
    fun snapshots(): Flow<Snapshot> = flow {
        while (true) {
            val s = Snapshot(
                queuePending = runCatching { queueDao.countByStatus(QueueStatus.PENDING) }.getOrDefault(0),
                queueDownloading = runCatching { queueDao.countByStatus(QueueStatus.DOWNLOADING) }.getOrDefault(0),
                queueSuccess = runCatching { queueDao.countByStatus(QueueStatus.SUCCESS) }.getOrDefault(0),
                queueFailed = runCatching { queueDao.countByStatus(QueueStatus.FAILED) }.getOrDefault(0),
                activeCount = runCatching {
                    // 只计真正在传输的那一 page —— Manager.loop 串行下载，
                    // 任意时刻 DOWNLOADING 状态最多 1 个。其它 INIT/PAUSED/FAILED 不算"正在下载"。
                    // Manager.content 非线程安全；snapshot 失败给 0，下个周期会再试
                    ArrayList(Manager.get().content)
                        .count { it.state == DownloadItem.DownloadState.DOWNLOADING }
                }.getOrDefault(0),
            )
            emit(s)
            delay(REFRESH_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val REFRESH_INTERVAL_MS = 1500L
    }
}
