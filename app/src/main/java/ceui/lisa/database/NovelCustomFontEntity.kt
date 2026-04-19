package ceui.lisa.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "novel_custom_font_table")
data class NovelCustomFontEntity(
    @PrimaryKey(autoGenerate = true)
    val fontId: Int = 0,
    val displayName: String,
    val relativePath: String,
    val originalUri: String,
    val byteSize: Long,
    val installedTime: Long = System.currentTimeMillis(),
)
