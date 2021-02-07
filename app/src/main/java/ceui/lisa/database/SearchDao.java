package ceui.lisa.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

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
    void insertMuteTag(MuteEntity muteEntity);

    @Update
    void updateMuteTag(MuteEntity muteEntity);

    //删除所有屏蔽的标签
    @Query("DELETE FROM tag_mute_table WHERE type = 0")
    void deleteAllMutedTags();

    @Query("DELETE FROM tag_mute_table WHERE type = 1 OR type = 2")
    void deleteMutedWorks();

    @Query("DELETE FROM tag_mute_table WHERE type = 3")
    void deleteMutedUser();

    @Query("SELECT * FROM tag_mute_table WHERE type = 0 ORDER BY searchTime DESC ")
    List<MuteEntity> getAllMutedTags();

    @Query("SELECT * FROM tag_mute_table WHERE type = 1 OR type = 2 ORDER BY searchTime DESC ")
    List<MuteEntity> getMutedWorks();

    @Query("SELECT * FROM tag_mute_table WHERE type = 1 ORDER BY searchTime DESC ")
    List<MuteEntity> getMutedIllusts();

    @Query("SELECT * FROM tag_mute_table WHERE type = 3 ORDER BY searchTime DESC ")
    List<MuteEntity> getMutedUser();

    @Delete
    void unMuteTag(MuteEntity userEntity);


    /**
     * 添加一个列表记录
     * @param uuidEntity
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertListWithUUID(UUIDEntity uuidEntity);


    @Query("SELECT * FROM uuid_list_table WHERE uuid = :paramUUID LIMIT 1")
    UUIDEntity getListByUUID(String paramUUID);
}
