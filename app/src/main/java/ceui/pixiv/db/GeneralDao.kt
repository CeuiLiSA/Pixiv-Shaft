package ceui.pixiv.db

import androidx.lifecycle.LiveData
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

    // ✅ 根据 entityType 查询数据，并按 updatedTime 排序，支持分页
    @Query("SELECT * FROM general_table WHERE recordType = :recordType ORDER BY updatedTime DESC LIMIT :limit OFFSET :offset")
    fun getByRecordType(
        recordType: Int,
        offset: Int,
        limit: Int = 30
    ): List<GeneralEntity> // 根据 entityType 返回数据，按 updatedTime 降序排列，支持分页

    // 根据 recordType 和 id 查询单条记录，若无匹配则返回 null
    @Query("SELECT * FROM general_table WHERE recordType = :recordType AND id = :id LIMIT 1")
    fun getByRecordTypeAndId(recordType: Int, id: Long): GeneralEntity?

    // ✅ 根据 recordType 和 id 删除记录
    @Query("DELETE FROM general_table WHERE recordType = :recordType AND id = :id")
    fun deleteByRecordTypeAndId(recordType: Int, id: Long)

    // ✅ 根据 entityType 和 id 查询对象是否被屏蔽，返回 LiveData<Boolean>
    @Query("SELECT COUNT(*) > 0 FROM general_table WHERE recordType = :recordType AND id = :id")
    fun isObjectBlocked(recordType: Int, id: Long): LiveData<Boolean>

    // 根据 recordType 返回所有 id 列表
    @Query("SELECT id FROM general_table WHERE recordType = :recordType")
    fun getAllIdsByRecordType(recordType: Int): List<Long>

    @Query("SELECT COUNT(*) FROM general_table WHERE recordType = :recordType")
    fun getCountByRecordType(recordType: Int): Int
}
