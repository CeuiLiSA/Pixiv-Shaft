package ceui.lisa.feature

import androidx.annotation.NonNull
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import ceui.lisa.models.IllustsBean
import java.io.Serializable

@Entity(tableName = "feature_table")
class FeatureEntity: Serializable {

    @PrimaryKey
    @NonNull
    var uuid: String = ""
    var dateTime: Long = 0L
    var starType: String = ""
    var userID = 0
    var isShowToolbar = false
    var name: String = ""
    var dataType: String = ""
    var illustJson: String = ""
    @Ignore
    var allIllust: List<IllustsBean> = ArrayList()

    override fun toString(): String {
        return "LikeIllustEntity(uuid='$uuid', dateTime=$dateTime, starType='$starType', userID=$userID, isShowToolbar=$isShowToolbar, name='$name', illustJson='$illustJson')"
    }
}