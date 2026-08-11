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

    // 批量写入(一批一个事务),云端历史物化回写(#989)整页落库用
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entities: List<GeneralEntity>)

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

    // 按 id 批量取 (id, updatedTime) 投影,云端历史物化回写(#989)做 LWW 比较,
    // 不拖 json 大字段。一次一页(≤100 id),不撞 SQLite 999 变量上限。
    @Query("SELECT id, updatedTime FROM general_table WHERE recordType = :recordType AND id IN (:ids)")
    fun getTimesByRecordTypeAndIds(recordType: Int, ids: List<Long>): List<RecordIdTime>

    // 云端回填(#989)的 keyset 分页,理由见 DownloadDao.getViewHistoryByTypeBefore:
    // offset 分页会被回填期间的新写入整体位移,页边界行被永久漏推。
    @Query("SELECT * FROM general_table WHERE recordType = :recordType AND updatedTime < :beforeTime ORDER BY updatedTime DESC LIMIT :limit")
    fun getByRecordTypeBefore(recordType: Int, beforeTime: Long, limit: Int): List<GeneralEntity>
}

/** [GeneralDao.getTimesByRecordTypeAndIds] 的投影结果。 */
data class RecordIdTime(
    val id: Long,
    val updatedTime: Long,
)
