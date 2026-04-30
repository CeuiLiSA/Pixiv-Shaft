package ceui.pixiv.ui.bulk

import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.loxia.ObjectPool
import ceui.pixiv.db.queue.DownloadQueueDao
import ceui.pixiv.db.queue.DownloadQueueEntity
import ceui.pixiv.db.queue.QueueStatus
import ceui.pixiv.db.queue.WorkType
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 细粒度 fetcher 事件：dialog 用它驱动 CLI 风格的实时 verbose 显示。
 *
 * 事件流（典型一页）：
 *   Started
 *   ── 第 1 页 ──
 *   Networking(1, "/v1/user/illusts")    ↩ 5xx?
 *   PageReceived(1, 30, latency=312ms)
 *   PoolUpdate(30)
 *   DbBatchStart(30)
 *   DbBatchDone(30, latency=1.2ms)
 *   Enqueued(total=30)
 *   ── 第 2 页 ──
 *   RateLimit(waitMs=1500)               ↩ 倒计时
 *   Networking(2, "<next_url 截断>")
 *   PageReceived(2, 30, latency=287ms)
 *   ...
 *   Done(total, elapsedMs)
 */
sealed class FetchEvent {
    data class Started(val taskName: String, val userId: Long) : FetchEvent()
    data class Networking(val pageIndex: Int, val endpoint: String) : FetchEvent()
    data class PageReceived(val pageIndex: Int, val pageSize: Int, val latencyMs: Long, val totalSoFar: Int) : FetchEvent()
    data class PoolUpdate(val size: Int) : FetchEvent()
    data class DbBatchStart(val size: Int) : FetchEvent()
    data class DbBatchDone(val size: Int, val latencyMs: Long) : FetchEvent()
    data class Enqueued(val totalSoFar: Int) : FetchEvent()
    data class RateLimit(val waitMs: Long) : FetchEvent()
    data class Done(val total: Int, val elapsedMs: Long, val pageCount: Int) : FetchEvent()
    data class Errored(val message: String, val pageIndex: Int) : FetchEvent()
}

/**
 * 抓取某作者的全部作品（插画/漫画），流式入队到 download_queue。
 * 每个微小步骤 emit 一个事件，让 CLI dialog 可以做到"一直在告诉用户当前在干嘛"。
 */
class AuthorWorksFetcher(
    private val userId: Long,
    private val type: String, // "illust" / "manga"
    private val taskName: String,
) {

    private val dao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }

    fun fetch(): Flow<FetchEvent> = flow {
        emit(FetchEvent.Started(taskName, userId))
        val startedAt = System.currentTimeMillis()
        var pageIndex = 0
        var totalSoFar = 0

        try {
            // —— 第 1 页 ——
            pageIndex = 1
            emit(FetchEvent.Networking(pageIndex, "/v1/user/illusts?type=$type"))
            val t0 = System.currentTimeMillis()
            var resp: ListIllust? = Retro.getAppApi()
                .getUserSubmitIllust(userId.toInt(), type)
                .awaitFirstSafe()
            val firstLatency = System.currentTimeMillis() - t0
            val firstList = (resp?.list ?: emptyList()).filter { !it.isGif }
            emit(FetchEvent.PageReceived(pageIndex, firstList.size, firstLatency, totalSoFar + firstList.size))

            if (firstList.isNotEmpty()) {
                totalSoFar += processPage(this, firstList, userId)
                emit(FetchEvent.Enqueued(totalSoFar))
            }

            // —— 后续页 ——
            while (resp != null) {
                val nextUrl = resp.next_url
                if (nextUrl.isNullOrEmpty()) break

                emit(FetchEvent.RateLimit(RATE_LIMIT_MS))
                delay(RATE_LIMIT_MS)

                pageIndex++
                emit(FetchEvent.Networking(pageIndex, abbrevUrl(nextUrl)))
                val tn = System.currentTimeMillis()
                resp = Retro.getAppApi().getNextIllust(nextUrl).awaitFirstSafe()
                val latency = System.currentTimeMillis() - tn
                val list = (resp?.list ?: emptyList()).filter { !it.isGif }
                emit(FetchEvent.PageReceived(pageIndex, list.size, latency, totalSoFar + list.size))

                if (list.isNotEmpty()) {
                    totalSoFar += processPage(this, list, userId)
                    emit(FetchEvent.Enqueued(totalSoFar))
                }
            }

            emit(FetchEvent.Done(totalSoFar, System.currentTimeMillis() - startedAt, pageIndex))
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // 用户取消：保留已入队的，不回滚
            throw cancellation
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "fetch failed userId=$userId type=$type page=$pageIndex")
            emit(FetchEvent.Errored(e.message ?: e::class.java.simpleName, pageIndex))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 处理一页：灌池 + 入队，每步发事件。返回入队数量。
     * 显式接 collector 而不是用 receiver —— member extension on outer-class FlowCollector
     * 在 Kotlin 当前稳定语法里不支持。
     */
    private suspend fun processPage(
        collector: FlowCollector<FetchEvent>,
        list: List<IllustsBean>,
        userId: Long,
    ): Int {
        collector.emit(FetchEvent.PoolUpdate(list.size))
        withContext(Dispatchers.Main.immediate) {
            list.forEach { illust ->
                runCatching { ObjectPool.updateIllust(illust) }
            }
        }

        collector.emit(FetchEvent.DbBatchStart(list.size))
        val tDb = System.currentTimeMillis()
        val batchBase = System.nanoTime()
        val rows = list.mapIndexed { i, illust ->
            DownloadQueueEntity(
                illustId = illust.id.toLong(),
                type = if (type == WorkType.MANGA) WorkType.MANGA else WorkType.ILLUST,
                seq = batchBase + i,
                sourceTag = "user:$userId",
                status = QueueStatus.PENDING,
            )
        }
        dao.appendBatch(rows)
        val dbLatency = System.currentTimeMillis() - tDb
        collector.emit(FetchEvent.DbBatchDone(list.size, dbLatency))

        QueueDownloadManager.notifyNewItems()
        return list.size
    }

    private fun abbrevUrl(url: String): String {
        if (url.length <= 64) return url
        return url.take(40) + "…" + url.takeLast(20)
    }

    companion object {
        private const val RATE_LIMIT_MS = 1500L
        private const val TAG = "AuthorWorksFetcher"
    }
}

/** RxJava2 -> suspend 单值（Fetcher 内部专用）。 */
private suspend fun <T : Any> Observable<T>.awaitFirstSafe(): T = suspendCancellableCoroutine { cont ->
    val disposable = subscribeOn(Schedulers.io())
        .firstOrError()
        .subscribe(
            { cont.resume(it) },
            { cont.resumeWithException(it) }
        )
    cont.invokeOnCancellation { disposable.dispose() }
}
