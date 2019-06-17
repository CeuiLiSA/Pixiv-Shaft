package ceui.lisa.database;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.OnConflictStrategy;
import android.arch.persistence.room.Query;

import java.util.List;

//推荐页面保存列表数据，调试用,免得每次都去请求接口，慢的一比
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
