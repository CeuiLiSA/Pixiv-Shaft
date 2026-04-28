package ceui.lisa.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface ComicReadingStatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ComicReadingStatsEntity entity);

    @Query("SELECT * FROM comic_reading_stats_table WHERE illustId = :illustId LIMIT 1")
    ComicReadingStatsEntity getByIllust(long illustId);

    @Query("SELECT * FROM comic_reading_stats_table WHERE illustId = :illustId LIMIT 1")
    LiveData<ComicReadingStatsEntity> observeByIllust(long illustId);

    @Query("SELECT SUM(totalDurationMs) FROM comic_reading_stats_table")
    long totalAllDurationMs();
}
