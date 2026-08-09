package ceui.pixiv.actionqueue.internal.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

internal enum class ActionStatus {
    PENDING,
    RUNNING,
    FAILED,
}

/**
 * 队列里的一行。
 *
 * FIFO 直接用自增主键 [id] 排序，不额外维护 seq 列 —— 重试时保留原 id，
 * 于是失败重排的动作不会插到后来入队的动作前面去。
 */
@Entity(
    tableName = "queued_action",
    indices = [
        // 消费者的热查询：status + notBefore 过滤，id 排序。三列覆盖索引，避免回表排序。
        Index(value = ["status", "notBefore", "id"]),
        // 入队合并时按 dedupeKey 删 PENDING。
        Index(value = ["dedupeKey", "status"]),
    ],
)
internal data class ActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val type: String,
    val dedupeKey: String,
    val payload: String,
    val gapMs: Long,
    val status: ActionStatus,
    /** 已经失败重试过几次，首次执行时为 0。 */
    val attempt: Int,
    /** 早于这个时刻不许执行。退避用；正常入队为 0。 */
    val notBefore: Long,
    val createdAt: Long,
    val lastError: String?,
)
