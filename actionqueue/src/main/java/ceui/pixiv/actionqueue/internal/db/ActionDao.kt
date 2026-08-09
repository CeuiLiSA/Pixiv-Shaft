package ceui.pixiv.actionqueue.internal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal interface ActionDao {

    @Insert
    suspend fun insert(entity: ActionEntity): Long

    @Query(
        "DELETE FROM queued_action " +
            "WHERE dedupeKey = :dedupeKey AND owner = :owner AND status = 'PENDING'"
    )
    suspend fun deletePendingByDedupeKey(dedupeKey: String, owner: String): Int

    @Query(
        "SELECT MAX(attempt) FROM queued_action " +
            "WHERE dedupeKey = :dedupeKey AND owner = :owner AND status = 'PENDING'"
    )
    suspend fun maxPendingAttempt(dedupeKey: String, owner: String): Int?

    @Query(
        "SELECT MAX(notBefore) FROM queued_action " +
            "WHERE dedupeKey = :dedupeKey AND owner = :owner AND status = 'PENDING'"
    )
    suspend fun maxPendingNotBefore(dedupeKey: String, owner: String): Long?

    /**
     * 合并式入队：同一 owner 的同一目标只保留最后一次意图。
     *
     * 必须在一个事务里删完再插 —— 分成两次调用的话，连点爱心时另一条线程可能
     * 在 delete 和 insert 之间插进来一行，结果两条都留下，等于没合并。
     *
     * 被删掉的行里如果有正在退避重试的，新行继承它们最大的 attempt 和 notBefore。
     * 不继承的后果：用户对着一个一直失败的目标反复点，每点一次重试预算就被清零，
     * 这条动作永远到不了 maxAttempts，既不判终态失败也不回滚，界面上的红心就一直挂着，
     * 而每次尝试还都在把整队冷却顶起来、连累其他所有收藏和关注。notBefore 同理 ——
     * 清零等于把已经排好的退避时刻丢了，进程重启后它会立刻重发。
     */
    @Transaction
    suspend fun insertCoalescing(entity: ActionEntity): Long {
        val inheritedAttempt = maxPendingAttempt(entity.dedupeKey, entity.owner) ?: 0
        val inheritedNotBefore = maxPendingNotBefore(entity.dedupeKey, entity.owner) ?: 0L
        deletePendingByDedupeKey(entity.dedupeKey, entity.owner)
        return insert(
            entity.copy(
                attempt = maxOf(entity.attempt, inheritedAttempt),
                notBefore = maxOf(entity.notBefore, inheritedNotBefore),
            )
        )
    }

    @Query(
        "SELECT * FROM queued_action " +
            "WHERE status = 'PENDING' AND owner = :owner AND notBefore <= :nowMs " +
            "ORDER BY id ASC LIMIT 1"
    )
    suspend fun nextRunnable(nowMs: Long, owner: String): ActionEntity?

    @Query("SELECT MIN(notBefore) FROM queued_action WHERE status = 'PENDING' AND owner = :owner")
    suspend fun earliestNotBefore(owner: String): Long?

    @Query(
        "SELECT COUNT(*) FROM queued_action " +
            "WHERE dedupeKey = :dedupeKey AND owner = :owner AND status = 'PENDING' AND id != :excludingId"
    )
    suspend fun countPendingByDedupeKey(dedupeKey: String, owner: String, excludingId: Long): Int

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

    /** 同上但不消耗重试预算，见 [ceui.pixiv.actionqueue.ActionOutcome.Retry.countsAsAttempt]。 */
    @Query("UPDATE queued_action SET status = 'PENDING', notBefore = :notBeforeMs WHERE id = :id")
    suspend fun rescheduleKeepingAttempt(id: Long, notBeforeMs: Long)

    @Query("UPDATE queued_action SET status = 'FAILED', lastError = :reason WHERE id = :id")
    suspend fun markFailed(id: Long, reason: String)

    /** 冷启动恢复。不按 owner 过滤：别的账号残留的 RUNNING 也该复位，等它自己登录回来再发。 */
    @Query("UPDATE queued_action SET status = 'PENDING' WHERE status = 'RUNNING'")
    suspend fun resurrectRunning(): Int

    @Query(
        "SELECT COUNT(*) FROM queued_action " +
            "WHERE owner = :owner AND (status = 'PENDING' OR status = 'RUNNING')"
    )
    suspend fun pendingCount(owner: String): Int

    @Query("SELECT COUNT(*) FROM queued_action WHERE owner = :owner AND status = 'FAILED'")
    suspend fun failedCount(owner: String): Int

    @Query(
        "UPDATE queued_action SET status = 'PENDING', attempt = 0, notBefore = :nowMs, " +
            "lastError = NULL WHERE owner = :owner AND status = 'FAILED'"
    )
    suspend fun retryAllFailed(nowMs: Long, owner: String): Int

    @Query("DELETE FROM queued_action WHERE owner = :owner AND status = 'FAILED'")
    suspend fun clearFailed(owner: String): Int

    @Query("SELECT value FROM queue_meta WHERE `key` = :key")
    suspend fun readMeta(key: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeMeta(entity: QueueMetaEntity)
}
