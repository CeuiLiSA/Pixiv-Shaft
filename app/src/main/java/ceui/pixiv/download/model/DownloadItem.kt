package ceui.pixiv.download.model

data class DownloadItem(
    val bucket: Bucket,
    val ext: String,
    val mime: String,
    val sourceUrl: String,
    val meta: ItemMeta,
)
