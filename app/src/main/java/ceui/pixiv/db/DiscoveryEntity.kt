package ceui.pixiv.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "discovery_table")
data class DiscoveryEntity(
    @PrimaryKey val illustId: Long,
    val illustJson: String,
    val score: Float,
    val source: String,
    val collectedTime: Long = System.currentTimeMillis(),
    val shown: Boolean = false,
    val authorId: Long = 0
)
