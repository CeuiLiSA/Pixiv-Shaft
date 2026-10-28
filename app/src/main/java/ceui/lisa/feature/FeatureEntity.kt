package ceui.lisa.feature

import androidx.annotation.NonNull
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import ceui.pixiv.api.model.Illust
import ceui.lisa.models.MangaSeriesItem
import java.io.Serializable

/**
 * v44 补了 `dateTime` 索引：`getFeatureList` 是 `ORDER BY dateTime DESC LIMIT ? OFFSET ?`，
 * 此前无索引 → 全表扫描 + 临时 B-tree 排序，每行还带一份 illustJson。
 */
@Entity(tableName = "feature_table", indices = [Index(value = ["dateTime"])])
class FeatureEntity : Serializable {

    @PrimaryKey
    @NonNull
    var uuid: String = ""
    var dateTime: Long = 0L
    var starType: String = ""
    var userID = 0L
    var illustID = 0L
    var illustTitle: String = ""
    var isShowToolbar = false
    var name: String = ""
    var dataType: String = ""
    var illustJson: String = ""
    var seriesId = 0L
    @Ignore
    var allIllust: List<Illust> = ArrayList()
    @Ignore
    var allMangaSeries: List<MangaSeriesItem> = ArrayList()

    override fun toString(): String {
        return "FeatureEntity(uuid='$uuid', dateTime=$dateTime, starType='$starType', userID=$userID, illustID=$illustID, illustTitle='$illustTitle', isShowToolbar=$isShowToolbar, name='$name', dataType='$dataType', illustJson='$illustJson', allIllust=$allIllust)"
    }
}
