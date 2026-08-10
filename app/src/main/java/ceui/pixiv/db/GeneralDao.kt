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
    fun getByRecordType(recordType: Int, offset: Int, limit: Int = 30): List<GeneralEntity> // 根据 entityType 返回数据，按 updatedTime 降序排列，支持分页

    // 根据 recordType 和 id 查询单条记录，若无匹配则返回 null
    @Query("SELECT * FROM general_table WHERE recordType = :recordType AND id = :id LIMIT 1")
    fun getByRecordTypeAndId(recordType: Int, id: Long): GeneralEntity?

    // ✅ 根据 recordType 和 id 删除记录
    @Query("DELETE FROM general_table WHERE recordType = :recordType AND id = :id")
    fun deleteByRecordTypeAndId(recordType: Int, id: Long)

    // 整段清空某个 recordType 的记录,用于浏览历史一键清空 (#886)。
    @Query("DELETE FROM general_table WHERE recordType = :recordType")
    fun deleteAllByRecordType(recordType: Int)

    // ✅ 根据 entityType 和 id 查询对象是否被屏蔽，返回 LiveData<Boolean>
    @Query("SELECT COUNT(*) > 0 FROM general_table WHERE recordType = :recordType AND id = :id")
    fun isObjectBlocked(recordType: Int, id: Long): LiveData<Boolean>

    // 根据 recordType 返回所有 id 列表
    @Query("SELECT id FROM general_table WHERE recordType = :recordType")
    fun getAllIdsByRecordType(recordType: Int): List<Long>

    // 同一 recordType 下再按 entityType 过滤（小说/插画共用 WATCH_LATER 时必须分开，
    // 否则小说 JSON 会被插画页解析成一张坏卡）。
    @Query("SELECT id FROM general_table WHERE recordType = :recordType AND entityType = :entityType")
    fun getAllIdsByRecordTypeAndEntityType(recordType: Int, entityType: Int): List<Long>

    @Query("SELECT * FROM general_table WHERE recordType = :recordType AND entityType = :entityType ORDER BY updatedTime DESC LIMIT :limit OFFSET :offset")
    fun getByRecordTypeAndEntityType(recordType: Int, entityType: Int, offset: Int, limit: Int = 30): List<GeneralEntity>

    // 只清空某个 recordType + entityType 的记录（小说「清空稍后再看」不能把插画一起删了）。
    @Query("DELETE FROM general_table WHERE recordType = :recordType AND entityType = :entityType")
    fun deleteAllByRecordTypeAndEntityType(recordType: Int, entityType: Int)
}
