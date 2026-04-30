package ceui.pixiv.db.queue

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface DownloadQueueDao {

    /**
     * 批量插入。Room 内部使用单事务，20000 条流式分批（每批 30）写入毫秒级。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<DownloadQueueEntity>): List<Long>

    /**
     * 取下一条待消费记录。FIFO 由 seq 决定。
     */
    @Query("SELECT * FROM download_queue WHERE status = :status ORDER BY seq ASC LIMIT 1")
    suspend fun nextByStatus(status: String = QueueStatus.PENDING): DownloadQueueEntity?

    @Query("UPDATE download_queue SET status = :newStatus, errorMsg = :err, finishedAt = :finishedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, newStatus: String, err: String? = null, finishedAt: Long? = null)

    @Query("UPDATE download_queue SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun bumpRetry(id: Long)

    /**
     * 冷启动恢复：把上次崩溃时残留的 DOWNLOADING 重置为 PENDING。
     */
    @Query("UPDATE download_queue SET status = '${QueueStatus.PENDING}' WHERE status = '${QueueStatus.DOWNLOADING}'")
    suspend fun resurrectInProgress(): Int

    @Query("SELECT COUNT(*) FROM download_queue WHERE status = :status")
    fun observeCountByStatus(status: String): LiveData<Int>

    @Query("SELECT COUNT(*) FROM download_queue WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    /**
     * UI 列表：分页加载，避免一次拉 20000 条。
     */
    @Query("SELECT * FROM download_queue ORDER BY seq ASC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<DownloadQueueEntity>

    @Query("SELECT * FROM download_queue ORDER BY seq ASC LIMIT :limit OFFSET :offset")
    fun observePage(limit: Int, offset: Int): LiveData<List<DownloadQueueEntity>>

    /**
     * 队列 tab 专用：排除 SUCCESS（已完成的不再"占着茅坑"）。
     * FAILED / CANCELED 仍展示，让用户能看到哪些没下成功。
     */
    @Query("SELECT * FROM download_queue WHERE status != '${QueueStatus.SUCCESS}' ORDER BY seq ASC LIMIT :limit OFFSET :offset")
    suspend fun pageActive(limit: Int, offset: Int): List<DownloadQueueEntity>

    @Query("SELECT COUNT(*) FROM download_queue WHERE status != '${QueueStatus.SUCCESS}'")
    suspend fun countActive(): Int

    @Query("DELETE FROM download_queue WHERE status = :status")
    suspend fun deleteByStatus(status: String): Int

    @Query("UPDATE download_queue SET status = '${QueueStatus.PENDING}', retryCount = 0, errorMsg = NULL, finishedAt = NULL WHERE status = '${QueueStatus.FAILED}'")
    suspend fun retryAllFailed(): Int

    @Query("DELETE FROM download_queue")
    suspend fun deleteAll()

    @Query("SELECT MAX(seq) FROM download_queue")
    suspend fun maxSeq(): Long?

    @Transaction
    suspend fun appendBatch(items: List<DownloadQueueEntity>) {
        if (items.isEmpty()) return
        insertAll(items)
    }
}
