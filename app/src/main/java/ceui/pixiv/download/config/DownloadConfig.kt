package ceui.pixiv.download.config

import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.template.DefaultTemplates
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
        // [Bucket.NovelSeries] / [Bucket.Caption] 是后加的桶：升级用户持久化的配置里
        // 没有它们的条目，空缺字段若照常掉回 defaults，合集/简介会拿到插画模板 + 图片卷
        // （Pictures/）。所以模板缺省用各自默认值，存储 / 覆盖策略先跟小说走（同为
        // Downloads 类文本产物），再退到 defaults。
        val inherited = when (bucket) {
            Bucket.NovelSeries, Bucket.Caption -> perBucket[Bucket.Novel]
            else -> null
        }
        // [Bucket.Backup] 同理：「全部重置」会把 perBucket 清空，备份若掉回 defaults.template
        // 就会按插画模板渲染成 ShaftImages/_0.json，所以模板缺省固定用备份自己的默认值。
        val fallbackTemplate = when (bucket) {
            Bucket.NovelSeries -> DefaultTemplates.NOVEL_SERIES
            Bucket.Caption -> DefaultTemplates.CAPTION
            Bucket.Backup -> DefaultTemplates.BACKUP
            else -> defaults.template
        }
        // 备份文件是 JSON 快照，冲突时不能覆盖也不能跳过：强制重命名，避免旧备份被静默替换。
        // SAF 下由系统 provider 自动追加 (1)；MediaStore 下由 Downloads.nextFreePath 生成 (1)。
        val overwrite = if (bucket == Bucket.Backup) {
            OverwritePolicy.Rename
        } else {
            override?.overwrite ?: inherited?.overwrite ?: defaults.overwrite
        }
        // 存储位置兜底：defaults.storage 是「图片」的位置（设置页只让用户选一次）。非图片桶
        // 没有 perBucket 覆盖时（「全部恢复默认」会清空 perBucket）不能照抄 —— 相册卷拒收
        // 非 image/*，备份 / 小说会静默写失败（真机复现：MIME type application/json cannot
        // be inserted into content://media/external/images/media）。
        val fallbackStorage = when (bucket) {
            Bucket.Illust, Bucket.Ugoira -> defaults.storage
            else -> defaults.storage.forDownloadsBucket()
        }
        return ResolvedBucket(
            template  = override?.template  ?: fallbackTemplate,
            storage   = override?.storage   ?: inherited?.storage   ?: fallbackStorage,
            overwrite = overwrite,
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
