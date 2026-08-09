package ceui.pixiv.actionqueue.internal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal interface ActionDao {

    @Insert
    suspend fun insert(entity: ActionEntity): Long

    @Query("DELETE FROM queued_action WHERE dedupeKey = :dedupeKey AND status = 'PENDING'")
    suspend fun deletePendingByDedupeKey(dedupeKey: String): Int

    /**
     * 合并式入队：同一目标只保留最后一次意图。
     *
     * 必须在一个事务里删完再插 —— 分成两次调用的话，连点爱心时另一条线程可能
     * 在 delete 和 insert 之间插进来一行，结果两条都留下，等于没合并。
     */
    @Transaction
    suspend fun insertCoalescing(entity: ActionEntity): Long {
        deletePendingByDedupeKey(entity.dedupeKey)
        return insert(entity)
    }

    @Query(
        "SELECT * FROM queued_action WHERE status = 'PENDING' AND notBefore <= :nowMs " +
            "ORDER BY id ASC LIMIT 1"
    )
    suspend fun nextRunnable(nowMs: Long): ActionEntity?

    @Query("SELECT MIN(notBefore) FROM queued_action WHERE status = 'PENDING'")
    suspend fun earliestNotBefore(): Long?

    @Query("UPDATE queued_action SET status = 'RUNNING' WHERE id = :id")
    suspend fun markRunning(id: Long)

    @Query("DELETE FROM queued_action WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE queued_action SET status = 'PENDING' WHERE id = :id")
    suspend fun releaseToPending(id: Long)

    @Query(
        "UPDATE queued_action SET status = 'PENDING', attempt = attempt + 1, " +
            "notBefore = :notBeforeMs WHERE id = :id"
    )
    suspend fun rescheduleForRetry(id: Long, notBeforeMs: Long)

    @Query("UPDATE queued_action SET status = 'FAILED', lastError = :reason WHERE id = :id")
    suspend fun markFailed(id: Long, reason: String)

    /** 冷启动恢复。 */
    @Query("UPDATE queued_action SET status = 'PENDING' WHERE status = 'RUNNING'")
    suspend fun resurrectRunning(): Int

    @Query("SELECT COUNT(*) FROM queued_action WHERE status = 'PENDING' OR status = 'RUNNING'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM queued_action WHERE status = 'FAILED'")
    suspend fun failedCount(): Int

    @Query(
        "UPDATE queued_action SET status = 'PENDING', attempt = 0, notBefore = :nowMs, " +
            "lastError = NULL WHERE status = 'FAILED'"
    )
    suspend fun retryAllFailed(nowMs: Long): Int

    @Query("DELETE FROM queued_action WHERE status = 'FAILED'")
    suspend fun clearFailed(): Int
}
