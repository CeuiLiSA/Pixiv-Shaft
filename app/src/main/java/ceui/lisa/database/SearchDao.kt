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

    @Query("DELETE FROM search_table WHERE pinned = 1")
    fun deleteAllPinned()

    // 固定标签全量返回，不能被 recent-history LIMIT 截掉（issue #524）
    @Query("SELECT * FROM search_table WHERE pinned = 1 ORDER BY searchTime DESC")
    fun getAllPinned(): List<SearchEntity>

    @Query("SELECT * FROM search_table WHERE pinned = 0 ORDER BY searchTime DESC LIMIT :limit")
    fun getRecentUnpinned(limit: Int): List<SearchEntity>

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

    /**
     * 某一类屏蔽作品的 id 全集（不带 tagJson）。
     *
     * 给 [ceui.pixiv.ui.common.MutedWorkStore] 预热内存名单用：判定跑在列表 bind 的热路径上，
     * 只需要 id，不该把每行几 KB 的作品 JSON 也读出来再扔掉。
     *
     * 「某个作品是否被屏蔽」一律问 store 的内存名单，不要为此再加按 type 拉全表的查询：
     * [ceui.lisa.core.Mapper] 是逐条判的，那种查询每调一次就把每行的作品 JSON 读出来再扔掉
     *（此前的 `mutedIllusts` / `mutedNovels` 就是这么用的，已删）。整表带 JSON 的读只有
     * 「屏蔽记录」页需要，走 [mutedWorks]。
     */
    @Query("SELECT id FROM tag_mute_table WHERE type = :type")
    fun getMutedWorkIds(type: Int): List<Int>

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