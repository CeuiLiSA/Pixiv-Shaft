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
)
