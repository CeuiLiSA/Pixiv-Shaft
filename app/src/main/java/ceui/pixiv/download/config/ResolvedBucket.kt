package ceui.pixiv.download.config

/**
 * A fully-resolved, non-nullable view of a bucket's config — produced by
 * [DownloadConfig.resolve]. Downstream code only ever sees this type, so there
 * is no fallback plumbing to reason about at the point of use.
 */
data class ResolvedBucket(
    val template: String,
    val storage: StorageChoice,
    val overwrite: OverwritePolicy,
)
