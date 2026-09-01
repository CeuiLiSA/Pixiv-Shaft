package ceui.pixiv.ui.bulk

import android.content.Context
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.pixiv.api.model.Illust
import ceui.pixiv.db.queue.DownloadQueueEntity
import ceui.pixiv.db.queue.QueueStatus
import ceui.pixiv.db.queue.WorkType
import com.hjq.toast.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import ceui.pixiv.services.appServices

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

    fun enqueueAndToast(context: Context, illusts: List<Illust>?) {
        // 全程用 ApplicationContext —— BulkSelectV3Fragment 调完会 finish()，
        // 后续 IO 协程跑到一半时 fragment context 已死，toast 会引用已销毁 Activity 崩溃。
        val appCtx: Context = context.applicationContext
        val queue = appCtx.appServices().queueDownloadManager

        val incomingSize = illusts?.size ?: 0
        if (incomingSize == 0) {
            Toaster.showShort(R.string.bulk_enqueue_empty)
            return
        }
        if (incomingSize > HARD_CAP) {
            Timber.tag(TAG).w("incoming list size $incomingSize > HARD_CAP $HARD_CAP, truncating")
            Toaster.showShort(appCtx.getString(R.string.bulk_enqueue_truncated, HARD_CAP))
        }
        // Toast 是同步、瞬时的，OK 在主线程；后面的 filter/插入全部 IO。
        Toaster.showShort(appCtx.getString(R.string.bulk_enqueue_started, incomingSize))

        // 拷贝引用进 IO 协程；filter 也在 IO，主线程立刻 return。
        val src = illusts!!
        scope.launch {
            try {
                val dao = AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
                // 不再过滤 isGif —— ugoira 走 consumer 内独立的 [downloadUgoira] 管线
                // （getGifPackage → zip → 解压 → encodeGif → V3 WriteHandle 写盘），
                // 跟 illust 同进一张 download_queue 表，状态机通用。
                val list = src.asSequence()
                    .take(HARD_CAP)
                    .toList()

                if (list.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toaster.showShort(R.string.bulk_enqueue_zero_after_filter)
                    }
                    return@launch
                }

                // 不灌 ObjectPool —— 这些 illust 来自当前可见列表页，已经在池里。
                // 重复 setValue 会让 LiveData observers 不必要地刷新一轮，list 大时（5000+）
                // 会导致 main thread jank。
                //
                // **序列化 illust 进 illustGson 列** —— 这样 consumer / 队列 tab 显示
                // 都不必再打 getIllustByID 接口；冷启动 100+ PENDING 一拥而上不会 429。
                // Gson 序列化一个 Illust ~30-80KB JSON，200 行 batch ≈ 6-16MB；
                // 全在 IO 线程做，跟 dao.appendBatch 同事务，不会卡主线程。
                list.chunked(BATCH_SIZE).forEach { batch ->
                    val batchBase = System.nanoTime()
                    val rows = batch.mapIndexed { i, illust ->
                        // type 跟 streaming fetcher 一致：isGif → UGOIRA，再分 manga / illust
                        val rowType = when {
                            illust.isGif() -> WorkType.UGOIRA
                            illust.type == WorkType.MANGA -> WorkType.MANGA
                            else -> WorkType.ILLUST
                        }
                        DownloadQueueEntity(
                            illustId = illust.id,
                            type = rowType,
                            seq = batchBase + i,
                            sourceTag = "legacy-batch",
                            status = QueueStatus.PENDING,
                            illustGson = runCatching { Shaft.sGson.toJson(illust) }.getOrNull(),
                        )
                    }
                    dao.appendBatch(rows)
                    // 每个 batch 都 poke UI（让用户看到行数在涨），不用等 resume
                    queue.queueListInvalidations.tryEmit(Unit)
                }

                // 全部入完才唤醒一次（之前是每批都 resume，浪费 N 次主线程标志写）
                queue.resume()

                withContext(Dispatchers.Main) {
                    Toaster.showShort(appCtx.getString(R.string.bulk_enqueue_done, list.size))
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "enqueueAndToast failed")
                withContext(Dispatchers.Main) {
                    Toaster.showShort(appCtx.getString(R.string.bulk_enqueue_failed, e.message ?: ""))
                }
            }
        }
    }

    private const val TAG = "LegacyBatchEnqueue"
}
