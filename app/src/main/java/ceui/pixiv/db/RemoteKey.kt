package ceui.pixiv.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "remote_keys")
data class RemoteKey(
    @PrimaryKey val recordType: Int,
    val nextPageUrl: String?
)