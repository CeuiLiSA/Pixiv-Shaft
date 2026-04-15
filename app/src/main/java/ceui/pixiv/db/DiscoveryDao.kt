package ceui.pixiv.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DiscoveryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: DiscoveryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entities: List<DiscoveryEntity>)

    @Query("SELECT * FROM discovery_table WHERE shown = 0 ORDER BY score DESC LIMIT :limit OFFSET :offset")
    fun getUnshown(limit: Int = 50, offset: Int = 0): List<DiscoveryEntity>

    @Query("UPDATE discovery_table SET shown = 1 WHERE illustId = :illustId")
    fun markShown(illustId: Long)

    @Query("SELECT COUNT(*) FROM discovery_table")
    fun count(): Int

    @Query("SELECT COUNT(*) FROM discovery_table WHERE shown = 0")
    fun countUnshown(): Int

    @Query("DELETE FROM discovery_table WHERE illustId NOT IN (SELECT illustId FROM discovery_table ORDER BY score DESC LIMIT :keep)")
    fun trimToSize(keep: Int = 2000)

    @Query("DELETE FROM discovery_table WHERE shown = 1")
    fun clearShown()

    @Query("SELECT COUNT(*) > 0 FROM discovery_table WHERE illustId = :illustId")
    fun exists(illustId: Long): Boolean

    @Query("DELETE FROM discovery_table")
    fun deleteAll()
}
