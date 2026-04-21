package ceui.pixiv.ui.novel.reader.model

data class SearchHit(
    val absoluteStart: Int,
    val absoluteEnd: Int,
    val pageIndex: Int,
    val snippet: String,
)
