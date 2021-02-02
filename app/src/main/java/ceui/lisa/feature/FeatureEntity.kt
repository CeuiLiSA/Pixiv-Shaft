package ceui.lisa.feature

import androidx.annotation.NonNull
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import ceui.lisa.models.IllustsBean
import java.io.Serializable

@Entity(tableName = "feature_table")
class FeatureEntity : Serializable {

    @PrimaryKey
    @NonNull
    var uuid: String = ""
    var dateTime: Long = 0L
    var starType: String = ""
    var userID = 0
    var illustID = 0
    var illustTitle: String = ""
    var isShowToolbar = false
    var name: String = ""
    var dataType: String = ""
    var illustJson: String = ""
    @Ignore
    var allIllust: List<IllustsBean> = ArrayList()

    override fun toString(): String {
        return "FeatureEntity(uuid='$uuid', dateTime=$dateTime, starType='$starType', userID=$userID, illustID=$illustID, illustTitle='$illustTitle', isShowToolbar=$isShowToolbar, name='$name', dataType='$dataType', illustJson='$illustJson', allIllust=$allIllust)"
    }
}
