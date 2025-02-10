package ceui.pixiv.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query


@Dao
interface GeneralDao {

    // ✅  插入数据，Room 正确解析 suspend 方法
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(generalEntity: GeneralEntity): Long  // 返回插入行 ID

    // ✅  查询所有数据，Room 正确解析 suspend 方法
    @Query("SELECT * FROM general_table")
    fun getAll(): List<GeneralEntity> //

    // ✅ 根据 entityType 查询数据
    @Query("SELECT * FROM general_table WHERE recordType = :recordType ORDER BY updatedTime DESC")
    fun getByRecordType(recordType: Int): List<GeneralEntity> // 根据 entityType 返回数据
}
