package ceui.pixiv.db.queue

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_queue",
    indices = [
        Index(value = ["status"]),
        Index(value = ["seq"]),
        Index(value = ["illustId"]),
    ]
)
data class DownloadQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val illustId: Long,
    val type: String = WorkType.ILLUST,
    val seq: Long,
    val sourceTag: String = "",
    val status: String = QueueStatus.PENDING,
    val errorMsg: String? = null,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    /**
     * 入队时序列化的 [ceui.pixiv.api.model.Illust] JSON。
     * 有了这个，consumer 处理 / 队列 tab 显示 都不需要再打 getIllustByID 接口
     * （冷启动 100+ 条 PENDING 一拥而上会被 pixiv 429 限流）。
     * 老版本入队的行可能为 null —— 此时 fallback 到 API。
     */
    val illustGson: String? = null,
)
