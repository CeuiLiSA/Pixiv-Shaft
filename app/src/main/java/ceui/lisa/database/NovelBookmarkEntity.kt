package ceui.lisa.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "novel_bookmark_table",
    indices = [
        Index(value = ["novelId"]),
        Index(value = ["createdTime"]),
    ],
)
data class NovelBookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val bookmarkId: Long = 0,
    val novelId: Long,
    val charIndex: Int,
    val pageIndex: Int,
    val preview: String,
    val note: String = "",
    val createdTime: Long = System.currentTimeMillis(),
)
