package ceui.pixiv.actionqueue.internal.db

import android.content.Context
import ceui.pixiv.actionqueue.ActionRequest
import ceui.pixiv.actionqueue.ActionStore
import ceui.pixiv.actionqueue.PendingAction
import ceui.pixiv.actionqueue.StoredAction

/** [ActionStore] 的 Room 实现。数据库实例懒建，首次访问发生在消费协程里（IO 线程）。 */
internal class RoomActionStore(private val context: Context) : ActionStore {

    private val dao: ActionDao by lazy { ActionQueueDatabase.get(context).actionDao() }

    override suspend fun enqueue(request: ActionRequest, nowMs: Long): Long {
        val entity = ActionEntity(
            type = request.type,
            dedupeKey = request.dedupeKey,
            payload = request.payload,
            gapMs = request.gapMs,
            status = ActionStatus.PENDING,
            attempt = 0,
            notBefore = 0L,
            createdAt = nowMs,
            lastError = null,
        )
        return if (request.coalesce) dao.insertCoalescing(entity) else dao.insert(entity)
    }

    override suspend fun nextRunnable(nowMs: Long): StoredAction? =
        dao.nextRunnable(nowMs)?.let { entity ->
            StoredAction(
                action = PendingAction(
                    id = entity.id,
                    type = entity.type,
                    dedupeKey = entity.dedupeKey,
                    payload = entity.payload,
                    attempt = entity.attempt,
                ),
                gapMs = entity.gapMs,
            )
        }

    override suspend fun earliestNotBeforeMs(): Long? = dao.earliestNotBefore()

    override suspend fun markRunning(id: Long): Unit = dao.markRunning(id)

    override suspend fun delete(id: Long): Unit = dao.deleteById(id)

    override suspend fun releaseToPending(id: Long): Unit = dao.releaseToPending(id)

    override suspend fun rescheduleForRetry(id: Long, notBeforeMs: Long): Unit =
        dao.rescheduleForRetry(id, notBeforeMs)

    override suspend fun markFailed(id: Long, reason: String): Unit =
        dao.markFailed(id, reason.take(MAX_ERROR_LEN))

    override suspend fun resurrectRunning(): Int = dao.resurrectRunning()

    override suspend fun pendingCount(): Int = dao.pendingCount()

    override suspend fun failedCount(): Int = dao.failedCount()

    override suspend fun retryAllFailed(nowMs: Long): Int = dao.retryAllFailed(nowMs)

    override suspend fun clearFailed(): Int = dao.clearFailed()

    private companion object {
        /** 异常 message 可能带整段 HTML 响应体，截断防止把库撑大。 */
        const val MAX_ERROR_LEN = 500
    }
}
