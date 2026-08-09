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
        // 消费者的热查询：status + owner + notBefore 过滤，id 排序。四列覆盖索引，避免回表排序。
        Index(value = ["status", "owner", "notBefore", "id"]),
        // 入队合并时按 owner + dedupeKey 删 PENDING。
        Index(value = ["dedupeKey", "owner", "status"]),
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
    /**
     * 入队时的登录用户 id。库是跨登录态持久的，取行必须按它过滤 ——
     * 否则 A 排队没发完的收藏会在 B 登录之后用 B 的 token 发出去。
     */
    val owner: String,
)

/**
 * 队列自己的键值位。目前只存整队冷却截止时刻。
 *
 * 冷却必须过进程：撞 429 之后要静默几分钟，而「后台待几分钟被系统回收」恰恰是这个窗口里
 * 最可能发生的事；只存内存的话下次启动会带着一整队 notBefore=0 的行原地重新撞限流。
 */
@Entity(tableName = "queue_meta")
internal data class QueueMetaEntity(
    @PrimaryKey val key: String,
    val value: Long,
)
