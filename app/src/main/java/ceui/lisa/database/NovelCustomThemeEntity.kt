package ceui.lisa.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "novel_custom_theme_table")
data class NovelCustomThemeEntity(
    @PrimaryKey(autoGenerate = true)
    val themeId: Int = 0,
    val name: String,
    val backgroundColor: Int,
    val textColor: Int,
    val secondaryTextColor: Int,
    val accentColor: Int,
    val linkColor: Int,
    val selectionColor: Int,
    val highlightColor: Int,
    val dividerColor: Int,
    val chapterTitleColor: Int,
    val isDark: Boolean,
    val backgroundImagePath: String? = null,
    val createdTime: Long = System.currentTimeMillis(),
)
