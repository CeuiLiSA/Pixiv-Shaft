package ceui.lisa.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

//保存下载历史记录
@Dao
public interface DownloadDao {

    /**
     * 添加一个下载记录
     * @param illustTask
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(DownloadEntity illustTask);


    /**
     * 删除一条下载记录
     * @param userEntity
     */
    @Delete
    void delete(DownloadEntity userEntity);

    /**
     * 获取全部下载记录
     * @param limit
     * @param offset
     * @return
     */
    @Query("SELECT * FROM illust_download_table ORDER BY downloadTime DESC LIMIT :limit OFFSET :offset")
    List<DownloadEntity> getAll(int limit, int offset);

    /**
     *
     */
    @Query("DELETE FROM illust_download_table")
    void deleteAllDownload();


    /**
     * 新增一个浏览历史
     * @param userEntity
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(IllustHistoryEntity userEntity);


    /**
     * 删除一个浏览历史
     * @param userEntity
     */
    @Delete
    void delete(IllustHistoryEntity userEntity);


    /**
     *
     */
    @Query("DELETE FROM illust_table")
    void deleteAllHistory();


    /**
     * 查询所有浏览历史
     * @param limit
     * @param offset
     * @return
     */
    @Query("SELECT * FROM illust_table ORDER BY time DESC LIMIT :limit OFFSET :offset")
    List<IllustHistoryEntity> getAllViewHistory(int limit, int offset);


    /**
     * 新增一个用户
     * @param userEntity
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertUser(UserEntity userEntity);


    /**
     * 删除一个用户
     * @param userEntity
     */
    @Delete
    void deleteUser(UserEntity userEntity);

    @Query("SELECT * FROM user_table ORDER BY loginTime DESC")
    List<UserEntity> getAllUser();
}
