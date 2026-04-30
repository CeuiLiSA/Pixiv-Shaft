package ceui.pixiv.ui.bulk

import android.content.Context
import android.widget.Toast
import ceui.lisa.R
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
import timber.log.Timber

/**
 * **唯一调用方**：[ceui.pixiv.ui.bulk.BulkSelectV3Fragment] 确认按钮。
 *
 * 设计目标：即使被异常调用方传 50000+ 项也不卡 UI。
 *  - 主线程只做：`isEmpty/size` 检查 + 一次 Toast + 一次 launch（O(1)）
 *  - 全部重活在 IO：filter / chunked / DB insert
 *  - 每 200 条一个事务批量 insert，跨批不停顿
 *  - resume() 只在所有批次结束后调一次（避免 N 次 paused 标志写）
 *
 * **不要新增调用方**。如果未来又出现"长按某 adapter 直接灌全部列表"的需求，请走
 * [ceui.lisa.interfaces.MultiDownload.startDownload] → [BulkSelectV3Fragment] 多选页，
 * 让用户先看到自己要下多少、有机会取消，避免误操作灌进几千几万项。
 */
object LegacyBatchEnqueue {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val BATCH_SIZE = 200
    /** 防御性硬上限：本接口理论上 BulkSelectV3Fragment 会让用户看清单后再确认，但极端误用兜底。 */
    private const val HARD_CAP = 100_000

    fun enqueueAndToast(context: Context, illusts: List<IllustsBean>?) {
        // 全程用 ApplicationContext —— BulkSelectV3Fragment 调完会 finish()，
        // 后续 IO 协程跑到一半时 fragment context 已死，toast 会引用已销毁 Activity 崩溃。
        val appCtx: Context = context.applicationContext

        val incomingSize = illusts?.size ?: 0
        if (incomingSize == 0) {
            Toast.makeText(appCtx, R.string.bulk_enqueue_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (incomingSize > HARD_CAP) {
            Timber.tag(TAG).w("incoming list size $incomingSize > HARD_CAP $HARD_CAP, truncating")
            Toast.makeText(appCtx, appCtx.getString(R.string.bulk_enqueue_truncated, HARD_CAP), Toast.LENGTH_SHORT).show()
        }
        // Toast 是同步、瞬时的，OK 在主线程；后面的 filter/插入全部 IO。
        Toast.makeText(appCtx, appCtx.getString(R.string.bulk_enqueue_started, incomingSize), Toast.LENGTH_SHORT).show()

        // 拷贝引用进 IO 协程；filter 也在 IO，主线程立刻 return。
        val src = illusts!!
        scope.launch {
            try {
                val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
                // GIF 滤掉（不走本队列，单独 ugoira 管线）；过滤本身在 IO。
                val list = src.asSequence()
                    .filter { !it.isGif }
                    .take(HARD_CAP)
                    .toList()

                if (list.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, R.string.bulk_enqueue_zero_after_filter, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 不灌 ObjectPool —— 这些 illust 来自当前可见列表页，已经在池里。
                // 重复 setValue 会让 LiveData observers 不必要地刷新一轮，list 大时（5000+）
                // 会导致 main thread jank。consumer 处理时拿不到会回退到 detail API。
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
                }

                // 全部入完才唤醒一次（之前是每批都 resume，浪费 N 次主线程标志写）
                QueueDownloadManager.resume()

                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, appCtx.getString(R.string.bulk_enqueue_done, list.size), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "enqueueAndToast failed")
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, appCtx.getString(R.string.bulk_enqueue_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private const val TAG = "LegacyBatchEnqueue"
}
