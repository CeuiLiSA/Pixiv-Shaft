package ceui.pixiv.db

import androidx.room.Entity
import androidx.room.Index
import ceui.lisa.activities.Shaft
import ceui.lisa.models.ModelObject
import ceui.pixiv.cache.ObjectPool

/**
 * v44 补了 `(recordType, updatedTime, id)` 索引。
 *
 * 这张表是**九种记录共用的一张表**（浏览插画/小说/作者历史、屏蔽、稍后再看、置顶作者……），
 * 每行的 `json` 是完整的 Illust/Novel JSON。此前一个索引都没有，于是
 * `WHERE recordType = ? ORDER BY updatedTime DESC LIMIT ?` 要把整张表（含所有浏览历史的
 * 大 JSON）扫一遍再排序；`PinnedUsers.loadForSearchRow` 还在**搜索页的 onResume 主线程**上
 * 调它。行数越多越慢，正是「高强度使用才卡」的一条腿。
 *
 * **尾列 `id` 不是随手加的**：`getAllIdsByRecordType`（`SELECT id ... WHERE recordType = ?`，
 * `DiscoveryPool.initialize` 冷启动要跑）只要 id 一列。只建 (recordType, updatedTime) 时它
 * 会从「覆盖扫描主键索引」退化成「走新索引 + 逐行回表取 id」，20k 行实测 6.5 ms → 106 ms。
 * 把 id 放进索引尾列后它重新变成覆盖扫描（6.0 ms），而按 updatedTime 排序的查询不受影响（0.7 ms）。
 */
@Entity(
    tableName = "general_table",
    primaryKeys = ["id", "recordType"], // 指定复合主键
    indices = [Index(value = ["recordType", "updatedTime", "id"])]
)
data class GeneralEntity(
    val id: Long,
    val json: String,
    val entityType: Int,
    val recordType: Int,
    val updatedTime: Long = System.currentTimeMillis()
) {
    inline fun <reified T> typedObject(): T {
        val obj = Shaft.sGson.fromJson(json, T::class.java)
        if (obj is ModelObject) {
            ObjectPool.update(obj)
        }
        return obj
    }
}
