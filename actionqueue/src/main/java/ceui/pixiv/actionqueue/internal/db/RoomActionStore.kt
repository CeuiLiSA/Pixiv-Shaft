package ceui.pixiv.actionqueue.internal.db

import android.content.Context
import ceui.pixiv.actionqueue.ActionRequest
import ceui.pixiv.actionqueue.ActionStore
import ceui.pixiv.actionqueue.PendingAction
import ceui.pixiv.actionqueue.StoredAction

/** [ActionStore] 的 Room 实现。数据库实例懒建，首次访问发生在消费协程里（IO 线程）。 */
internal class RoomActionStore(
    private val context: Context,
    private val databaseName: String,
) : ActionStore {

    private val dao: ActionDao by lazy {
        ActionQueueDatabase.get(context, databaseName).actionDao()
    }

    override suspend fun enqueue(request: ActionRequest, nowMs: Long, owner: String): Long {
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
            owner = owner,
        )
        return if (request.coalesce) dao.insertCoalescing(entity) else dao.insert(entity)
    }

    override suspend fun nextRunnable(nowMs: Long, owner: String): StoredAction? =
        dao.nextRunnable(nowMs, owner)?.let { entity ->
            StoredAction(
                action = PendingAction(
                    id = entity.id,
                    type = entity.type,
                    dedupeKey = entity.dedupeKey,
                    payload = entity.payload,
                    attempt = entity.attempt,
                    owner = entity.owner,
                ),
                gapMs = entity.gapMs,
            )
        }

    override suspend fun earliestNotBeforeMs(owner: String): Long? = dao.earliestNotBefore(owner)

    override suspend fun hasPending(
        dedupeKey: String,
        owner: String,
        excludingId: Long,
    ): Boolean = dao.countPendingByDedupeKey(dedupeKey, owner, excludingId) > 0

    override suspend fun markRunning(id: Long): Unit = dao.markRunning(id)

    override suspend fun delete(id: Long): Unit = dao.deleteById(id)

    override suspend fun releaseToPending(id: Long): Unit = dao.releaseToPending(id)

    override suspend fun rescheduleForRetry(id: Long, notBeforeMs: Long, countAttempt: Boolean) {
        if (countAttempt) {
            dao.rescheduleForRetry(id, notBeforeMs)
        } else {
            dao.rescheduleKeepingAttempt(id, notBeforeMs)
        }
    }

    override suspend fun markFailed(id: Long, reason: String): Unit =
        dao.markFailed(id, reason.take(MAX_ERROR_LEN))

    override suspend fun resurrectRunning(): Int = dao.resurrectRunning()

    override suspend fun pendingCount(owner: String): Int = dao.pendingCount(owner)

    override suspend fun failedCount(owner: String): Int = dao.failedCount(owner)

    override suspend fun retryAllFailed(nowMs: Long, owner: String): Int =
        dao.retryAllFailed(nowMs, owner)

    override suspend fun clearFailed(owner: String): Int = dao.clearFailed(owner)

    override suspend fun pruneFailed(owner: String, keepLatest: Int): Int =
        dao.pruneFailed(owner, keepLatest.coerceAtLeast(0))

    override suspend fun loadCooldownUntilMs(): Long = dao.readMeta(KEY_COOLDOWN_UNTIL) ?: 0L

    override suspend fun saveCooldownUntilMs(untilMs: Long) {
        dao.writeMeta(QueueMetaEntity(KEY_COOLDOWN_UNTIL, untilMs))
    }

    private companion object {
        /** 异常 message 可能带整段 HTML 响应体，截断防止把库撑大。 */
        const val MAX_ERROR_LEN = 500

        const val KEY_COOLDOWN_UNTIL = "cooldown_until_ms"
    }
}
