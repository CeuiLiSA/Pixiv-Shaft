package ceui.lisa.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Aggregated reading statistics for a single novel.
 * Daily rollups live in [DailyReadingStatsEntity].
 */
@Entity(tableName = "novel_reading_stats_table")
data class NovelReadingStatsEntity(
    @PrimaryKey
    val novelId: Long,
    val lastCharIndex: Int = 0,
    val lastPageIndex: Int = 0,
    val totalPageCount: Int = 0,
    val lastReadTime: Long = 0,
    val firstReadTime: Long = 0,
    val openCount: Int = 0,
    val totalDurationMs: Long = 0,
    val totalFlips: Int = 0,
    val totalCharsRead: Long = 0,
    val completed: Boolean = false,
)

@Entity(
    tableName = "novel_daily_reading_stats_table",
    primaryKeys = ["dayEpoch"],
)
data class DailyReadingStatsEntity(
    val dayEpoch: Int, // days since 1970-01-01 in the device timezone
    val durationMs: Long = 0,
    val charsRead: Long = 0,
    val flipCount: Int = 0,
    val novelsTouched: Int = 0,
)
