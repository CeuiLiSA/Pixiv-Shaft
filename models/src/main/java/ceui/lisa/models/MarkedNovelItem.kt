package ceui.lisa.models

import java.io.Serializable

class MarkedNovelItem : Serializable {
    class NovelMarker : Serializable {
        var isCancelled = false
        var page = 1
    }
    lateinit var novel: NovelBean
    lateinit var novel_marker: NovelMarker
}