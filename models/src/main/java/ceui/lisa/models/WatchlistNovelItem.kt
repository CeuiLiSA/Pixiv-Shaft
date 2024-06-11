package ceui.lisa.models

import java.io.Serializable

class WatchlistNovelItem : Serializable {
    var id: Int = 0
    var title: String = ""
    var url: String? = null
    var mask_text: String? = null
    var published_content_count: Int = 0
    var last_published_content_datetime: String? = null
        get() {
            return field!!.substring(0, 10)
        }
    var latest_content_id: Int? = null
    var user: UserBean? = null
}
