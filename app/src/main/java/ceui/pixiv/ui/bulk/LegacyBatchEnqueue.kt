package ceui.pixiv.ui.bulk

import android.content.Context
import android.widget.Toast
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.models.IllustsBean
import ceui.pixiv.db.queue.DownloadQueueEntity
import ceui.pixiv.db.queue.QueueStatus
import ceui.pixiv.db.queue.WorkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 旧入口（列表长按 / popup 的"批量下载"）→ 把当前可见的 illust 列表入新持久化队列。
 *
 * 全部 IO 线程，主线程只发指令。每 200 条一个事务批量 insert，20000 条不会卡。
 */
object LegacyBatchEnqueue {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val BATCH_SIZE = 200

    fun enqueueAndToast(context: Context, illusts: List<IllustsBean>?) {
        val list = illusts.orEmpty().filter { !it.isGif }
        if (list.isEmpty()) {
            Toast.makeText(context, "没有可入队的作品", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, "已发起入队 ${list.size} 项", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
                // 不再灌 ObjectPool —— 这些 illust 来自当前可见列表页，已经在池里。
                // 重复 setValue 会让 LiveData observers（RecyclerView cell、各种 ViewModel）
                // 不必要地刷新一轮，list 大时（5000+）会导致 main thread jank。
                // 如果未来 consumer 处理时确实拿不到，resolveIllustsBean 会回退到 detail API。
                list.chunked(BATCH_SIZE).forEach { batch ->
                    val batchBase = System.nanoTime()
                    val rows = batch.mapIndexed { i, illust ->
                        DownloadQueueEntity(
                            illustId = illust.id.toLong(),
                            type = WorkType.ILLUST,
                            seq = batchBase + i,
                            sourceTag = "legacy-batch",
                            status = QueueStatus.PENDING,
                        )
                    }
                    dao.appendBatch(rows)
                    QueueDownloadManager.notifyNewItems()
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "入队完成，共 ${list.size} 项", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "入队失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
