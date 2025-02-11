package ceui.pixiv.db

import androidx.room.Entity
import ceui.lisa.activities.Shaft
import ceui.lisa.models.ModelObject
import ceui.loxia.ObjectPool

@Entity(
    tableName = "general_table",
    primaryKeys = ["id", "recordType"] // 指定复合主键
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
