package ceui.lisa.database;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.OnConflictStrategy;
import android.arch.persistence.room.Query;

import java.util.List;

//保存浏览历史记录
@Dao
public interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(DownloadEntity illustTask);

    @Insert
    void insertAll(List<DownloadEntity> userEntities);

    @Delete
    void delete(DownloadEntity userEntity);


    @Query("SELECT * FROM illust_download_table ORDER BY downloadTime DESC LIMIT :limit OFFSET :offset")
    List<DownloadEntity> getAll(int limit, int offset);

    @Query("SELECT * FROM illust_download_table ORDER BY downloadTime")
    List<DownloadEntity> getAll();

    @Query("DELETE FROM illust_download_table")
    void deleteAll();
}
