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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CLI 风格 dialog 订阅的事件流。
 *
 * 设计：边抓边入队（streaming），抓到一页就立刻批量 insert，UI 不感知 DB 操作。
 */
sealed class FetchEvent {
    data class Started(val taskName: String, val userId: Long) : FetchEvent()
    data class PageFetched(val pageIndex: Int, val pageSize: Int, val totalSoFar: Int) : FetchEvent()
    data class Enqueued(val totalSoFar: Int) : FetchEvent()
    data class Done(val total: Int, val elapsedMs: Long) : FetchEvent()
    data class Errored(val message: String) : FetchEvent()
    data class RateLimit(val waitMs: Long) : FetchEvent()
}

/**
 * 抓取某作者的全部作品（插画/漫画），流式入队到 download_queue。
 *
 * 关键性能保证（针对一次性 20000 作品场景）：
 *  - 每抓到一页（30 条）→ 立刻在 [Dispatchers.IO] 上批量 [DownloadQueueDao.insertAll]（单事务）
 *  - 整个 flow 在 [Dispatchers.IO]，UI 只订阅事件
 *  - 不存储 illust 详情 JSON，只存 illustId（DB 体积可忽略）
 *  - [ObjectPool.updateIllust] 切到 [Dispatchers.Main]：MutableLiveData.setValue 必须在主线程，
 *    否则会走 try-catch 内的 postValue fallback —— 上万次 postValue 会把主线程 Handler 队列压爆，
 *    这是 v1 实现里真正的卡顿根源。整页一次性切，单次 dispatch 把 30 条灌进池子。
 *  - seq 用 [System.nanoTime] 单调递增，多个 fetcher 并发时不会冲突。
 */
class AuthorWorksFetcher(
    private val userId: Long,
    private val type: String, // "illust" / "manga"
    private val taskName: String,
) {

    // 懒加载：默认参数会在构造调用栈（Main 线程）触发 DB 打开 + v33 migration 跑在 Main，
    // 首次启动可能数百 ms 卡顿。改成 by lazy，首次访问发生在 flow {} 协程里（IO）。
    private val dao: DownloadQueueDao by lazy {
        AppDatabase.getAppDatabase(Shaft.getContext()).downloadQueueDao()
    }

    fun fetch(): Flow<FetchEvent> = flow {
        emit(FetchEvent.Started(taskName, userId))
        val startedAt = System.currentTimeMillis()
        var pageIndex = 0
        var totalSoFar = 0
        // nanoTime 全局单调，避免并发 fetcher 之间 seq 冲突；本批内用 i 偏移保证页内顺序。
        // 每条记录 seq = batchBase + i；batchBase 每页重新取 nanoTime，足够稀疏。
        try {
            var resp: ListIllust? = Retro.getAppApi()
                .getUserSubmitIllust(userId.toInt(), type)
                .awaitFirstSafe()

            while (resp != null) {
                pageIndex++
                // GIF/ugoira 走单独的 ZIP+解压管线，无法可靠 await settled，从队列里剔除
                val list: List<IllustsBean> = (resp.list ?: emptyList()).filter { !it.isGif }

                if (list.isNotEmpty()) {
                    // 1) 整页一次性灌 ObjectPool（Main 线程，单次 dispatch）。
                    //    ObjectPool.store 非线程安全；写过程中其他线程可能并发读，try-catch 兜底
                    //    避免一次失败把整个 fetch 流挂掉。
                    withContext(Dispatchers.Main.immediate) {
                        list.forEach { illust ->
                            runCatching { ObjectPool.updateIllust(illust) }
                        }
                    }
                    // 2) 批量 insert（IO 线程单事务）
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
                    totalSoFar += rows.size
                    emit(FetchEvent.PageFetched(pageIndex, rows.size, totalSoFar))
                    emit(FetchEvent.Enqueued(totalSoFar))
                    QueueDownloadManager.notifyNewItems()
                } else {
                    emit(FetchEvent.PageFetched(pageIndex, 0, totalSoFar))
                }

                val nextUrl = resp.next_url
                if (nextUrl.isNullOrEmpty()) {
                    break
                }
                emit(FetchEvent.RateLimit(waitMs = RATE_LIMIT_MS))
                delay(RATE_LIMIT_MS)
                resp = Retro.getAppApi().getNextIllust(nextUrl).awaitFirstSafe()
            }
            emit(FetchEvent.Done(totalSoFar, System.currentTimeMillis() - startedAt))
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // 用户取消：保留已入队的，不回滚
            throw cancellation
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "fetch failed userId=$userId type=$type")
            emit(FetchEvent.Errored(e.message ?: e::class.java.simpleName))
        }
    }.flowOn(Dispatchers.IO)

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
