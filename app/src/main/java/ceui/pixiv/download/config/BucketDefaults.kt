package ceui.pixiv.download.config

/**
 * Required defaults layer: every field is non-nullable. Acts as the fallback
 * for [BucketConfig]. Must be fully resolved before a [DownloadConfig] is
 * constructed (validated at construction time).
 */
data class BucketDefaults(
    val template: String,
    val storage: StorageChoice,
    val overwrite: OverwritePolicy = OverwritePolicy.Replace,
)
