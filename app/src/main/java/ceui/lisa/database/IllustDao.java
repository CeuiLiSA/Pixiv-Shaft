package ceui.lisa.database;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.OnConflictStrategy;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.Update;

import java.util.List;

@Dao
public interface IllustDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(IllustEntity userEntity);

    @Insert
    void insertAll(List<IllustEntity> userEntities);

    @Delete
    void delete(IllustEntity userEntity);


    @Query("SELECT * FROM illust_table ORDER BY time DESC")
    List<IllustEntity> getAll();
}
