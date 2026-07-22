package ceui.pixiv.download.importer

import android.content.Context
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.loxia.fetchFullIllustDetail
import ceui.pixiv.ui.bulk.FetchEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber

/**
 * 给 [DownloadImporter] 导进来的记录补上真实的标题 / 作者 / 封面。
 *
 * 导入本身是**完全离线**的，写进去的 illustGson 只有一个 id。这一步是可选的第二程：
 * 逐个作品回 `v1/illust/detail` 拉完整数据覆盖回去，已完成列表的卡片就和正常下载的
 * 记录长得一样了。不跑也不影响"已下载"判定 —— 那个只查 illustId 索引。
 *
 * 三条硬约束：
 *  - **限速**。沿用 [ceui.pixiv.ui.bulk.BulkObjectFetcher] 的 2s/请求，不给 pixiv 添堵。
 *  - **可断点续跑**。每处理完一批就把剩余 id 写回 [ImportEnrichQueue]，杀进程 / 断网 /
 *    用户取消之后重进能接着跑（思路同 [ceui.lisa.database.DownloadIdBackfill]）。
 *  - **失败即出队**。作品被删 / 不可见时不该无限重试卡住整条队列，记一条 warning 就过。
 *
 * 进度 UI 直接复用 [ceui.pixiv.ui.bulk.FetchProgressDialog] —— 它本来就是为"限速翻页
 * 拉 API"设计的。
 */
object ImportMetadataEnricher {

    /** 与 BulkObjectFetcher.RATE_LIMIT_MS 保持一致。 */
    private const val RATE_LIMIT_MS = 2000L

    /** 每处理这么多个作品把剩余队列写回一次 MMKV。 */
    private const val FLUSH_EVERY = 20

    private const val TAG = "ImportEnricher"

    fun pendingCount(): Int = ImportEnrichQueue.peek().size

    /**
     * 逐个补全队列里的作品。返回的 flow 冷启动，被 collect 时才开跑；取消即停，
     * 已完成的部分不回滚，剩余部分留在队列里。
     */
    fun enrich(context: Context): Flow<FetchEvent> = flow {
        val dao = AppDatabase.getAppDatabase(context.applicationContext).downloadDao()
        val pending = ArrayDeque(ImportEnrichQueue.peek())
        val total = pending.size
        emit(FetchEvent.Started("import-enrich", "$total 个作品"))

        if (total == 0) {
            emit(FetchEvent.Done(0, 0L, 0))
            return@flow
        }

        val startedAt = System.currentTimeMillis()
        var done = 0
        var failed = 0
        var sinceFlush = 0

        try {
            while (pending.isNotEmpty()) {
                val illustId = pending.removeFirst()
                val index = done + failed + 1

                emit(FetchEvent.Networking(index, "/v1/illust/detail?illust_id=$illustId"))
                val t0 = System.currentTimeMillis()
                val illust = fetchFullIllustDetail(illustId)
                val latency = System.currentTimeMillis() - t0

                if (illust == null) {
                    // 作品已删 / 不可见 / 这一次网络失败。都不重试 —— 队列不能被一条
                    // 死记录卡住，用户可以之后再导入一次重新入队。
                    failed++
                    emit(FetchEvent.Warning("illust=$illustId 拉取失败，跳过"))
                } else {
                    val json = Shaft.sGson.toJson(illust)
                    runCatching { dao.updateIllustGsonByIllustId(illustId, json) }
                        .onFailure { Timber.tag(TAG).w(it, "写回 illustGson 失败 id=%d", illustId) }
                    done++
                    emit(FetchEvent.PageReceived(index, 1, latency, done))
                }

                if (++sinceFlush >= FLUSH_EVERY) {
                    flush(pending)
                    sinceFlush = 0
                }

                if (pending.isNotEmpty()) {
                    emit(FetchEvent.RateLimit(RATE_LIMIT_MS))
                    delay(RATE_LIMIT_MS)
                }
            }
            flush(pending)
            emit(FetchEvent.Done(done, System.currentTimeMillis() - startedAt, done + failed))
        } catch (ce: CancellationException) {
            // 用户取消：把还没处理的写回去，下次能接着跑。取消是控制流，照常往上抛。
            flush(pending)
            throw ce
        } catch (e: Exception) {
            flush(pending)
            Timber.tag(TAG).e(e, "补全中断，剩余 %d 个留在队列里", pending.size)
            emit(FetchEvent.Errored(e.message ?: e.javaClass.simpleName, done + failed))
        }
    }.flowOn(Dispatchers.IO)

    /** 整队写回。全量覆盖而不是逐条 remove —— 后者会让 MMKV 反复重写同一个大 value。 */
    private fun flush(pending: Collection<Long>) {
        ImportEnrichQueue.clear()
        if (pending.isNotEmpty()) ImportEnrichQueue.enqueue(pending)
    }
}
