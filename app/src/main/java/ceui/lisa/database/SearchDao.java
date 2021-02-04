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

    @Delete
    void deleteSearchEntity(SearchEntity searchEntity);

    @Query("DELETE FROM search_table")
    void deleteAll();


    @Query("SELECT * FROM search_table ORDER BY searchTime DESC LIMIT :limit")
    List<SearchEntity> getAll(int limit);


    //添加一个屏蔽标签
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertMuteTag(TagMuteEntity muteEntity);

    //删除所有屏蔽的标签
    @Query("DELETE FROM tag_mute_table")
    void deleteAllMutedTags();

    @Query("SELECT * FROM tag_mute_table ORDER BY searchTime DESC ")
    List<TagMuteEntity> getAllMutedTags();

    @Delete
    void unMuteTag(TagMuteEntity userEntity);


    /**
     * 添加一个列表记录
     * @param uuidEntity
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertListWithUUID(UUIDEntity uuidEntity);


    @Query("SELECT * FROM uuid_list_table WHERE uuid = :paramUUID LIMIT 1")
    UUIDEntity getListByUUID(String paramUUID);
}
