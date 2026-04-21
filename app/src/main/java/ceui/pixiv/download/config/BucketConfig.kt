package ceui.pixiv.download.config

/**
 * A bucket's per-user knobs. Any field left `null` falls back to the
 * [DownloadConfig.defaults] layer (Template Method — superclass supplies a
 * hook, subclass-style override).
 */
data class BucketConfig(
    val template: String? = null,
    val storage: StorageChoice? = null,
    val overwrite: OverwritePolicy? = null,
)
