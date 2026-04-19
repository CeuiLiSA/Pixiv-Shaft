package ceui.lisa.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface NovelBookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NovelBookmarkEntity): Long

    @Update
    suspend fun update(entity: NovelBookmarkEntity)

    @Delete
    suspend fun delete(entity: NovelBookmarkEntity)

    @Query("DELETE FROM novel_bookmark_table WHERE bookmarkId = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM novel_bookmark_table WHERE novelId = :novelId")
    suspend fun deleteAllForNovel(novelId: Long): Int

    @Query("SELECT * FROM novel_bookmark_table WHERE novelId = :novelId ORDER BY charIndex ASC")
    suspend fun getForNovel(novelId: Long): List<NovelBookmarkEntity>

    @Query("SELECT * FROM novel_bookmark_table WHERE novelId = :novelId ORDER BY charIndex ASC")
    fun observeForNovel(novelId: Long): LiveData<List<NovelBookmarkEntity>>

    @Query("SELECT COUNT(*) FROM novel_bookmark_table WHERE novelId = :novelId AND charIndex = :charIndex")
    suspend fun existsAt(novelId: Long, charIndex: Int): Int

    @Query("SELECT * FROM novel_bookmark_table ORDER BY createdTime DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<NovelBookmarkEntity>
}
