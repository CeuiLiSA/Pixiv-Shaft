package ceui.lisa.models

import ceui.loxia.Novel
import java.io.Serializable

class MarkedNovelItem : Serializable {
    class NovelMarker : Serializable {
        var isCancelled = false
        var page = 1
    }
    lateinit var novel: Novel
    lateinit var novel_marker: NovelMarker
}
