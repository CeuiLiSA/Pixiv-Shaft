package ceui.lisa.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ComicBookmarkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ComicBookmarkEntity entry);

    @Query("DELETE FROM comic_bookmark_table WHERE bookmarkId = :id")
    void deleteById(long id);

    @Query("DELETE FROM comic_bookmark_table WHERE illustId = :illustId")
    void clearForIllust(long illustId);

    @Query("SELECT * FROM comic_bookmark_table WHERE illustId = :illustId ORDER BY createdTime DESC")
    LiveData<List<ComicBookmarkEntity>> observeForIllust(long illustId);

    @Query("SELECT * FROM comic_bookmark_table WHERE illustId = :illustId ORDER BY createdTime DESC")
    List<ComicBookmarkEntity> listForIllust(long illustId);
}
