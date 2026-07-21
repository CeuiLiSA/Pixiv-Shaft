package ceui.pixiv.download.config

import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.template.PageNumbering

/**
 * The full user-editable download configuration.
 *
 * Layered:
 *   [defaults]    — required fallback for every bucket
 *   [perBucket]   — optional per-bucket override; any null field inherits
 *
 * [resolve] produces a [ResolvedBucket] that is guaranteed to be complete —
 * downstream code never sees nullable fields.
 *
 * Note: [Bucket.TempCache] is not user-configurable; it always uses
 * [StorageChoice.AppCache] and a fixed template hard-wired inside [Downloads].
 * This map must therefore not contain an entry for it, and [defaults] applies
 * only to user-visible buckets.
 */
data class DownloadConfig(
    val version: Int = VERSION,
    val defaults: BucketDefaults,
    val perBucket: Map<Bucket, BucketConfig> = emptyMap(),
    val wifiOnly: Boolean = false,
    /** true = page numbering starts at 1 (p1, p2, …); false = starts at 0 (p0, p1, …). */
    val pageIndexFrom1: Boolean = true,
    /** true = zero-pad `{page}` so multi-page works sort correctly in galleries (#721). */
    val padPageNumber: Boolean = false,
) {

    /** The two page-numbering knobs as the template layer wants them. */
    val pageNumbering: PageNumbering
        get() = PageNumbering(indexFrom1 = pageIndexFrom1, padded = padPageNumber)

    fun resolve(bucket: Bucket): ResolvedBucket {
        require(bucket != Bucket.TempCache) {
            "Bucket.TempCache is not user-configurable; resolve it through Downloads, not DownloadConfig"
        }
        val override = perBucket[bucket]
        return ResolvedBucket(
            template  = override?.template  ?: defaults.template,
            storage   = override?.storage   ?: defaults.storage,
            overwrite = override?.overwrite ?: defaults.overwrite,
        )
    }

    fun withBucket(bucket: Bucket, config: BucketConfig): DownloadConfig =
        copy(perBucket = perBucket + (bucket to config))

    fun withoutOverride(bucket: Bucket): DownloadConfig =
        copy(perBucket = perBucket - bucket)

    companion object {
        const val VERSION = 1
    }
}
