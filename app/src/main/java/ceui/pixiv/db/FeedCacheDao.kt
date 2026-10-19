package ceui.pixiv.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 首屏快照的存取。阻塞式（对齐本仓 DAO 约定 + allowMainThreadQueries），调用方
 * （RoomFeedCacheBackend）负责切到 IO 线程。
 */
@Dao
interface FeedCacheDao {

    @Query("SELECT * FROM feed_cache_table WHERE cacheKey = :key LIMIT 1")
    fun find(key: String): FeedCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: FeedCacheEntity)

    @Query("DELETE FROM feed_cache_table WHERE cacheKey = :key")
    fun delete(key: String)

    /**
     * 账号被从本地移除：清掉它的全部首屏快照。
     *
     * [FeedCacheEntity.cacheKey] 的形状是 `slot#uid#载荷类型全名`（见 FeedFirstPageCache），
     * uid 夹在两个 `#` 中间，所以这样匹配既不误伤别的账号，也不会漏掉任何 slot、任何载荷类型。
     */
    @Query("DELETE FROM feed_cache_table WHERE cacheKey LIKE '%#' || :uid || '#%'")
    fun deleteByUid(uid: Long): Int

    /**
     * LRU 淘汰：只保留最新的 [keepCount] 行，其余按 savedAt 从旧到新删掉。
     * 防止换号 / 换 slot 遗留的死行无界堆积（每次 upsert 后调一次）。
     */
    @Query(
        "DELETE FROM feed_cache_table WHERE cacheKey NOT IN " +
            "(SELECT cacheKey FROM feed_cache_table ORDER BY savedAt DESC LIMIT :keepCount)"
    )
    fun trimToNewest(keepCount: Int)
}
