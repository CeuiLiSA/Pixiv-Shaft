package ceui.lisa.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface NovelCustomThemeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NovelCustomThemeEntity): Long

    @Update
    suspend fun update(entity: NovelCustomThemeEntity)

    @Delete
    suspend fun delete(entity: NovelCustomThemeEntity)

    @Query("DELETE FROM novel_custom_theme_table WHERE themeId = :id")
    suspend fun deleteById(id: Int): Int

    @Query("SELECT * FROM novel_custom_theme_table ORDER BY createdTime DESC")
    suspend fun getAll(): List<NovelCustomThemeEntity>

    @Query("SELECT * FROM novel_custom_theme_table ORDER BY createdTime DESC")
    fun observeAll(): LiveData<List<NovelCustomThemeEntity>>

    @Query("SELECT * FROM novel_custom_theme_table WHERE themeId = :id LIMIT 1")
    suspend fun findById(id: Int): NovelCustomThemeEntity?
}
