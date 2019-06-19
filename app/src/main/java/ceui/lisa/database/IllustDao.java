//package ceui.lisa.database;
//
//import android.arch.persistence.room.Dao;
//import android.arch.persistence.room.Delete;
//import android.arch.persistence.room.Insert;
//import android.arch.persistence.room.OnConflictStrategy;
//import android.arch.persistence.room.Query;
//
//import java.util.List;
//
////保存浏览历史记录
//@Dao
//public interface IllustDao {
//
//    @Insert(onConflict = OnConflictStrategy.REPLACE)
//    void insert(IllustHistoryEntity userEntity);
//
//    @Insert
//    void insertAll(List<IllustHistoryEntity> userEntities);
//
//    @Delete
//    void delete(IllustHistoryEntity userEntity);
//
//
//    @Query("SELECT * FROM illust_table ORDER BY time DESC LIMIT :limit OFFSET :offset")
//    List<IllustHistoryEntity> getAll(int limit, int offset);
//}
