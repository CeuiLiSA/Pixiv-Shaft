package ceui.pixiv.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RemoteKeyDao {
    @Query("SELECT * FROM remote_keys WHERE recordType = :recordType")
    fun getRemoteKey(recordType: Int): RemoteKey?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(remoteKey: RemoteKey)

    @Query("DELETE FROM remote_keys WHERE recordType = :recordType")
    fun deleteByRecordType(recordType: Int)
}