package ceui.lisa.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface SearchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(searchEntity: SearchEntity)

    @Query("SELECT * FROM search_table WHERE id = :id LIMIT 1")
    fun getSearchEntity(id: Int): SearchEntity

    @Delete
    fun deleteSearchEntity(searchEntity: SearchEntity)

    @Query("DELETE FROM search_table")
    fun deleteAll()

    @Query("DELETE FROM search_table WHERE pinned = 0")
    fun deleteAllUnpinned()

    /**
     * Get all search history
     * @param limit The maximum number of search history
     * @return The search history List
     * */
    @Query("SELECT * FROM search_table ORDER BY pinned DESC, searchTime DESC LIMIT :limit")
    fun getAll(limit: Int): List<SearchEntity>

    @get:Query("SELECT * FROM search_table")
    val allSearchEntities: List<SearchEntity>

    //添加一个屏蔽标签
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMuteTag(muteEntity: MuteEntity)

    @Update
    fun updateMuteTag(muteEntity: MuteEntity)

    //删除所有屏蔽的标签
    @Query("DELETE FROM tag_mute_table WHERE type = 0")
    fun deleteAllMutedTags()

    @Query("DELETE FROM tag_mute_table WHERE type = 3")
    fun deleteAllMutedUsers()

    @Query("DELETE FROM tag_mute_table WHERE type = 1 OR type = 2")
    fun deleteMutedWorks()

    @Query("DELETE FROM tag_mute_table WHERE type = 3")
    fun deleteMutedUser()

    @get:Query("SELECT * FROM tag_mute_table WHERE type = 0 ORDER BY searchTime DESC ")
    val allMutedTags: List<MuteEntity>

    @get:Query("SELECT * FROM tag_mute_table WHERE type = 1 OR type = 2 ORDER BY searchTime DESC ")
    val mutedWorks: List<MuteEntity>

    @get:Query("SELECT * FROM tag_mute_table WHERE type = 1 ORDER BY searchTime DESC ")
    val mutedIllusts: List<MuteEntity>

    @Query("SELECT * FROM tag_mute_table WHERE type = 3 ORDER BY searchTime DESC LIMIT :limit OFFSET :offset")
    fun getMutedUser(limit: Int, offset: Int): List<MuteEntity>

    @Query("SELECT * FROM tag_mute_table WHERE type = 3 AND id = :userID LIMIT 1")
    fun getUserMuteEntityByID(userID: Int): MuteEntity?

    @Query("SELECT * FROM tag_mute_table WHERE type = 3 AND id = :userID LIMIT 1")
    fun getUserMuteEntityByIDLiveData(userID: Int): LiveData<MuteEntity>

    @Query("SELECT * FROM tag_mute_table WHERE type = 1 AND id = :illustId LIMIT 1")
    fun getIllustMuteEntityByID(illustId: Int): LiveData<MuteEntity>

    @Query("SELECT * FROM tag_mute_table WHERE type = 4 AND id = :userID LIMIT 1")
    fun getBlockMuteEntityByID(userID: Int): MuteEntity?

    @Delete
    fun deleteMuteEntity(muteEntity: MuteEntity)

    @get:Query("SELECT * FROM tag_mute_table")
    val allMuteEntities: List<MuteEntity>

    @Delete
    fun unMuteTag(userEntity: MuteEntity)

    /**
     * 添加一个列表记录
     * @param uuidEntity
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertListWithUUID(uuidEntity: UUIDEntity)

    @Query("SELECT * FROM uuid_list_table WHERE uuid = :paramUUID LIMIT 1")
    fun getListByUUID(paramUUID: String): UUIDEntity
}