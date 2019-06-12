package ceui.lisa.database;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.OnConflictStrategy;
import android.arch.persistence.room.Query;

import java.util.List;

//推荐页面保存列表数据，调试用
@Dao
public interface IllustRecmdDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(IllustRecmdEntity userEntity);

    @Insert
    void insertAll(List<IllustRecmdEntity> userEntities);

    @Delete
    void delete(IllustRecmdEntity userEntity);


    @Query("SELECT * FROM illust_recmd_table")
    List<IllustRecmdEntity> getAll();


}
