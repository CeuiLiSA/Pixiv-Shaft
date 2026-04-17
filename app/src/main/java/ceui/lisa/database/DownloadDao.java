package ceui.lisa.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

import ceui.lisa.feature.FeatureEntity;

//保存下载历史记录
@Dao
public interface DownloadDao {

    /**
     * 添加一个下载记录
     *
     * @param illustTask
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(DownloadEntity illustTask);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertDownloading(DownloadingEntity entity);

    @Delete
    void deleteDownloading(DownloadingEntity entity);

    /**
     * 删除一条下载记录
     *
     * @param userEntity
     */
    @Delete
    void delete(DownloadEntity userEntity);

    /**
     * 获取全部下载记录
     *
     * @param limit
     * @param offset
     * @return
     */
    @Query("SELECT * FROM illust_download_table ORDER BY downloadTime DESC LIMIT :limit OFFSET :offset")
    List<DownloadEntity> getAll(int limit, int offset);

    /**
     * 判断是否存在指定插画 id 的下载记录（通过 illustGson 中的 "id":xxx 片段匹配）。
     * 兼容 id 作为最后一个字段（以 `}` 结尾）和非末尾字段（以 `,` 结尾）两种 Gson 序列化顺序。
     */
    @Query("SELECT COUNT(*) > 0 FROM illust_download_table WHERE " +
            "illustGson LIKE '%\"id\":' || :illustId || ',%' OR " +
            "illustGson LIKE '%\"id\":' || :illustId || '}%'")
    boolean hasDownloadRecordByIllustId(long illustId);

    @Query("SELECT * FROM illust_downloading_table")
    List<DownloadingEntity> getAllDownloading();

    @Query("SELECT * FROM illust_downloading_table ORDER BY rowid DESC LIMIT :limit")
    List<DownloadingEntity> getRecentDownloading(int limit);

    @Query("DELETE FROM illust_downloading_table WHERE rowid NOT IN (SELECT rowid FROM illust_downloading_table ORDER BY rowid DESC LIMIT :keep)")
    void trimDownloading(int keep);

    /**
     *
     */
    @Query("DELETE FROM illust_download_table")
    void deleteAllDownload();

    @Query("DELETE FROM illust_downloading_table")
    void deleteAllDownloading();


    /**
     * 新增一个浏览历史
     *
     * @param illustHistoryEntity
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(IllustHistoryEntity illustHistoryEntity);

    /**
     * 删除一个浏览历史
     *
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
     * 分页查询所有浏览历史
     *
     * @param limit
     * @param offset
     * @return
     */
    @Query("SELECT * FROM illust_table ORDER BY time DESC LIMIT :limit OFFSET :offset")
    List<IllustHistoryEntity> getAllViewHistory(int limit, int offset);

    /**
     * 查询所有浏览历史
     *
     * @return
     */
    @Query("SELECT * FROM illust_table")
    List<IllustHistoryEntity> getAllViewHistoryEntities();

    /**
     * 浏览历史总条数
     */
    @Query("SELECT COUNT(*) FROM illust_table")
    int getViewHistoryCount();


    /**
     * 新增一个用户
     *
     * @param userEntity
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertUser(UserEntity userEntity);


    /**
     * 删除一个用户
     *
     * @param userEntity
     */
    @Delete
    void deleteUser(UserEntity userEntity);

    @Query("SELECT * FROM user_table ORDER BY loginTime DESC")
    List<UserEntity> getAllUser();

    @Query("SELECT * FROM user_table limit 1")
    UserEntity getCurrentUser();

    @Query("SELECT * FROM upload_image_table ORDER BY uploadTime DESC")
    List<ImageEntity> getUploadedImage();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertUploadedImage(ImageEntity imageEntity);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertFeature(FeatureEntity holder);

    @Query("SELECT * FROM feature_table ORDER BY dateTime DESC LIMIT :limit OFFSET :offset")
    List<FeatureEntity> getFeatureList(int limit, int offset);

    @Delete
    void deleteFeature(FeatureEntity userEntity);

    @Query("DELETE FROM feature_table")
    void deleteAllFeature();

    @Query("SELECT * FROM feature_table")
    List<FeatureEntity> getAllFeatureEntities();
}
