package ceui.lisa.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

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


    @Query("SELECT * FROM illust_recmd_table ORDER BY time DESC LIMIT 30 OFFSET 0 ")
    List<IllustRecmdEntity> getAll();

}
