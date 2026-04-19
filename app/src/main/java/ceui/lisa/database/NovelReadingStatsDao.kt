package ceui.lisa.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NovelReadingStatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NovelReadingStatsEntity)

    @Query("SELECT * FROM novel_reading_stats_table WHERE novelId = :novelId LIMIT 1")
    suspend fun get(novelId: Long): NovelReadingStatsEntity?

    @Query("SELECT * FROM novel_reading_stats_table WHERE novelId = :novelId LIMIT 1")
    fun observe(novelId: Long): LiveData<NovelReadingStatsEntity?>

    @Query("SELECT * FROM novel_reading_stats_table ORDER BY lastReadTime DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NovelReadingStatsEntity>

    @Query("UPDATE novel_reading_stats_table SET lastCharIndex = :charIndex, lastPageIndex = :pageIndex, totalPageCount = :total, lastReadTime = :time WHERE novelId = :novelId")
    suspend fun updateProgress(novelId: Long, charIndex: Int, pageIndex: Int, total: Int, time: Long): Int

    @Query("UPDATE novel_reading_stats_table SET totalDurationMs = totalDurationMs + :deltaMs, totalFlips = totalFlips + :flips, totalCharsRead = totalCharsRead + :chars WHERE novelId = :novelId")
    suspend fun incrementCounters(novelId: Long, deltaMs: Long, flips: Int, chars: Long): Int

    @Query("SELECT SUM(totalDurationMs) FROM novel_reading_stats_table")
    suspend fun totalReadingDuration(): Long?

    @Query("SELECT COUNT(*) FROM novel_reading_stats_table WHERE lastReadTime > 0")
    suspend fun totalNovelsOpened(): Int
}

@Dao
interface DailyReadingStatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyReadingStatsEntity)

    @Query("SELECT * FROM novel_daily_reading_stats_table WHERE dayEpoch = :day LIMIT 1")
    suspend fun get(day: Int): DailyReadingStatsEntity?

    @Query("SELECT * FROM novel_daily_reading_stats_table WHERE dayEpoch >= :from AND dayEpoch <= :to ORDER BY dayEpoch ASC")
    suspend fun range(from: Int, to: Int): List<DailyReadingStatsEntity>

    @Query("SELECT * FROM novel_daily_reading_stats_table ORDER BY dayEpoch DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DailyReadingStatsEntity>

    @Query("UPDATE novel_daily_reading_stats_table SET durationMs = durationMs + :deltaMs, charsRead = charsRead + :chars, flipCount = flipCount + :flips WHERE dayEpoch = :day")
    suspend fun incrementForDay(day: Int, deltaMs: Long, chars: Long, flips: Int): Int
}
