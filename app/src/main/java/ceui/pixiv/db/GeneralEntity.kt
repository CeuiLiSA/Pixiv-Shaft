package ceui.pixiv.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "general_table")
data class GeneralEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String?,
    val description: String?
)
