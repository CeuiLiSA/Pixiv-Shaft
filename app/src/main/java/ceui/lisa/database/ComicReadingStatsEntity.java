package ceui.lisa.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** V3 漫画阅读器：单作品累计阅读统计。 */
@Entity(tableName = "comic_reading_stats_table")
public class ComicReadingStatsEntity {

    @PrimaryKey
    public long illustId;

    public int lastPageIndex;
    public int totalPageCount;
    public long lastReadTime;
    public long firstReadTime;
    public int openCount;
    public long totalDurationMs;
    public int totalFlips;
    public int completed; // 0 = false, 1 = true (已读至末页)

    public ComicReadingStatsEntity() {}
}
