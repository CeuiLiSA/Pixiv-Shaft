package ceui.lisa.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SearchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(SearchEntity searchEntity);


    @Query("DELETE FROM search_table")
    void deleteAll();


    @Query("SELECT * FROM search_table ORDER BY searchTime DESC LIMIT :limit")
    List<SearchEntity> getAll(int limit);
}
