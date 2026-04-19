package ceui.lisa.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NovelCustomFontDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NovelCustomFontEntity): Long

    @Delete
    suspend fun delete(entity: NovelCustomFontEntity)

    @Query("DELETE FROM novel_custom_font_table WHERE fontId = :id")
    suspend fun deleteById(id: Int): Int

    @Query("SELECT * FROM novel_custom_font_table ORDER BY installedTime DESC")
    suspend fun getAll(): List<NovelCustomFontEntity>

    @Query("SELECT * FROM novel_custom_font_table ORDER BY installedTime DESC")
    fun observeAll(): LiveData<List<NovelCustomFontEntity>>

    @Query("SELECT * FROM novel_custom_font_table WHERE fontId = :id LIMIT 1")
    suspend fun findById(id: Int): NovelCustomFontEntity?
}
