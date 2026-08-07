package ceui.pixiv.db.queue

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

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

    /**
     * 同时刻多 illust 在飞时，"下一条 PENDING"必须排除已经在 inflight 集合中的
     * id —— 否则同一行可能被同一轮 fillSlots 重复 take 导致 DOWNLOADING 重复。
     * exclude 可空：调用方没有 inflight 时直接传 [emptyList]。
     */
    @Query("SELECT * FROM download_queue WHERE status = :status AND id NOT IN (:excludeIds) ORDER BY seq ASC LIMIT 1")
    suspend fun nextByStatusExcluding(status: String, excludeIds: List<Long>): DownloadQueueEntity?

    @Query("SELECT * FROM download_queue WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DownloadQueueEntity?

    /** @return 受影响行数；行已被「清空全部」删掉时为 0，调用方据此跳过批量汇总计数。 */
    @Query("UPDATE download_queue SET status = :newStatus, errorMsg = :err, finishedAt = :finishedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, newStatus: String, err: String? = null, finishedAt: Long? = null): Int

    @Query("UPDATE download_queue SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun bumpRetry(id: Long)

    /**
     * 断点续传的「进度看门狗」用：本次尝试推进了字节前沿，就把重试计数清零，让
     * 抖动线路只要每次都往前挪就能一直续，而不是在固定 MAX_RETRY 次后放弃
     * （详见 [ceui.pixiv.ui.bulk.QueueDownloadManager] 的 progressWatermark）。
     */
    @Query("UPDATE download_queue SET retryCount = 0 WHERE id = :id")
    suspend fun resetRetry(id: Long)

    /**
     * 冷启动恢复：把上次崩溃时残留的 DOWNLOADING 重置为 PENDING。
     */
    @Query("UPDATE download_queue SET status = '${QueueStatus.PENDING}' WHERE status = '${QueueStatus.DOWNLOADING}'")
    suspend fun resurrectInProgress(): Int

    @Query("SELECT COUNT(*) FROM download_queue WHERE status = :status")
    fun observeCountByStatus(status: String): LiveData<Int>

    /**
     * Reactive 计数：Room InvalidationTracker 在 download_queue 表 INSERT/UPDATE/DELETE
     * 时自动 emit 新值。配合 [combine] / [Flow.collect] 替代原来的 timer 轮询。
     */
    @Query("SELECT COUNT(*) FROM download_queue WHERE status = :status")
    fun flowCountByStatus(status: String): Flow<Int>

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

    /**
     * 列表展示用的轻量投影：**不带** [DownloadQueueEntity.illustGson]。
     *
     * 为什么独立：单条 JSON 5–30KB，UI 上限 5000 行 → 一次拉就把 ~50–150MB
     * 钉死在 ListAdapter.currentList，叠加其他 retain 直接把 256MB heap 撑爆，
     * UI 任何后续 setText / new byte[] 全部 OOM。列表上根本用不到 JSON：
     * 标题 / 缩略图走 ObjectPool；点击进 VActivity 时再单条 [getById] 反序列化。
     */
    @Query("SELECT id, illustId, type, seq, status, retryCount FROM download_queue WHERE status != '${QueueStatus.SUCCESS}' ORDER BY seq ASC LIMIT :limit")
    suspend fun pageActiveLight(limit: Int): List<DownloadQueueRow>

    /**
     * Reactive 队列：UI 端直接 collect 这个 Flow，Room 在 download_queue 表
     * 任何变更时自动重新 emit 完整快照。替代原来的 1.5s 轮询 + pageActive 翻页拼装。
     * 上限 [limit] 控制 RecyclerView 一次渲染的最大行数；consumer 不受此限。
     */
    @Query("SELECT * FROM download_queue WHERE status != '${QueueStatus.SUCCESS}' ORDER BY seq ASC LIMIT :limit")
    fun flowActive(limit: Int): Flow<List<DownloadQueueEntity>>

    @Query("SELECT COUNT(*) FROM download_queue WHERE status != '${QueueStatus.SUCCESS}'")
    suspend fun countActive(): Int

    @Query("DELETE FROM download_queue WHERE status = :status")
    suspend fun deleteByStatus(status: String): Int

    @Query("UPDATE download_queue SET status = '${QueueStatus.PENDING}', retryCount = 0, errorMsg = NULL, finishedAt = NULL WHERE status = '${QueueStatus.FAILED}'")
    suspend fun retryAllFailed(): Int

    @Query("DELETE FROM download_queue")
    suspend fun deleteAll()

    /** 给 BulkDownloadCacheCleaner 估"清出来多少字节"用。 */
    @Query("SELECT IFNULL(SUM(LENGTH(illustGson)), 0) FROM download_queue")
    suspend fun sumIllustGsonBytes(): Long

    @Query("SELECT MAX(seq) FROM download_queue")
    suspend fun maxSeq(): Long?

    @Transaction
    suspend fun appendBatch(items: List<DownloadQueueEntity>) {
        if (items.isEmpty()) return
        insertAll(items)
    }
}
