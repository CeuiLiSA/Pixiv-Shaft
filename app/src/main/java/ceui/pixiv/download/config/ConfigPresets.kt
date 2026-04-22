package ceui.pixiv.download.config

import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.template.DefaultTemplates

/**
 * Pre-baked full-configuration presets the user can one-click apply.
 * Prototype pattern — each preset is a fully-cloneable starting point.
 *
 * Each preset is responsible for its own per-bucket map; presets do NOT
 * inherit from each other so the end state of "apply preset X" is
 * deterministic and does not depend on what was there before.
 *
 * 预设覆盖面：
 * - [shaftClassic] — 与 4.5.7 及之前版本字节级一致（插画/动图），旧数据零迁移识别。
 * - [modern] — 新的 `Shaft/Illusts/{author} ({author_id})/` 结构，R18/AI 嵌套子目录。
 * - [flat] — 一个根目录全摊开，不区分 R18/AI/作者。
 * - [byDate] — 按年/月分组。
 * - [byAuthor] — 按作者分组，不按 R18/AI 分。
 * - [byAuthorAndDate] — 作者 + 年月双层分组。
 * - [bySeries] — 小说按系列分组，图按作者分组。
 * - [minimal] — 仅用 ID，最短路径，适合文件管理器手动管理。
 * - [rFilter] — 强制 R18/AI 子目录；内部再按作者分。
 */
object ConfigPresets {

    /** 4.5.7 及之前的默认路径，重新下载时可匹配旧文件。 */
    fun shaftClassic(imagesStorage: StorageChoice, downloadsStorage: StorageChoice): DownloadConfig =
        DownloadConfig(
            defaults = BucketDefaults(
                template = DefaultTemplates.ILLUST,
                storage  = imagesStorage,
            ),
            perBucket = mapOf(
                Bucket.Ugoira to BucketConfig(template = DefaultTemplates.UGOIRA, storage = imagesStorage),
                Bucket.Novel  to BucketConfig(template = DefaultTemplates.NOVEL,  storage = downloadsStorage),
                Bucket.Backup to BucketConfig(template = DefaultTemplates.BACKUP, storage = downloadsStorage),
                Bucket.Log    to BucketConfig(template = DefaultTemplates.LOG,    storage = downloadsStorage),
            ),
        )

    /** 下载重构后一直到 4.5.8 版本的风格（按作者分 + R18/AI 嵌套目录）。 */
    fun modern(imagesStorage: StorageChoice, downloadsStorage: StorageChoice): DownloadConfig =
        DownloadConfig(
            defaults = BucketDefaults(
                template = "Shaft/Illusts/[?R18:R18/][?AI:AI/]{author} ({author_id})/{title} {id}[?p>1: p{page}].{ext}",
                storage  = imagesStorage,
            ),
            perBucket = mapOf(
                Bucket.Ugoira to BucketConfig(
                    template = "Shaft/Ugoira/{author} ({author_id})/{title} {id}.gif",
                    storage  = imagesStorage,
                ),
                Bucket.Novel  to BucketConfig(
                    template = "Shaft/Novels/{author} ({author_id})/{title} {id}.txt",
                    storage  = downloadsStorage,
                ),
                Bucket.Backup to BucketConfig(template = DefaultTemplates.BACKUP, storage = downloadsStorage),
                Bucket.Log    to BucketConfig(template = DefaultTemplates.LOG,    storage = downloadsStorage),
            ),
        )

    fun flat(imagesStorage: StorageChoice, downloadsStorage: StorageChoice): DownloadConfig =
        DownloadConfig(
            defaults = BucketDefaults(
                template = "Shaft/{title} {id}[?p>1: p{page1}].{ext}",
                storage  = imagesStorage,
            ),
            perBucket = mapOf(
                Bucket.Ugoira to BucketConfig(template = "Shaft/{title} {id}.gif", storage = imagesStorage),
                Bucket.Novel  to BucketConfig(template = "Shaft/{title} {id}.txt", storage = downloadsStorage),
                Bucket.Backup to BucketConfig(template = DefaultTemplates.BACKUP,  storage = downloadsStorage),
                Bucket.Log    to BucketConfig(template = DefaultTemplates.LOG,     storage = downloadsStorage),
            ),
        )

    fun byDate(imagesStorage: StorageChoice, downloadsStorage: StorageChoice): DownloadConfig =
        DownloadConfig(
            defaults = BucketDefaults(
                template = "Shaft/{created:yyyy}/{created:yyyy-MM}/{title} {id}[?p>1: p{page1}].{ext}",
                storage  = imagesStorage,
            ),
            perBucket = mapOf(
                Bucket.Ugoira to BucketConfig(
                    template = "Shaft/{created:yyyy}/{created:yyyy-MM}/{title} {id}.gif",
                    storage  = imagesStorage,
                ),
                Bucket.Novel  to BucketConfig(
                    template = "Shaft/Novels/{created:yyyy}/{created:yyyy-MM}/{title} {id}.txt",
                    storage  = downloadsStorage,
                ),
                Bucket.Backup to BucketConfig(template = DefaultTemplates.BACKUP, storage = downloadsStorage),
                Bucket.Log    to BucketConfig(template = DefaultTemplates.LOG,    storage = downloadsStorage),
            ),
        )

    fun byAuthor(imagesStorage: StorageChoice, downloadsStorage: StorageChoice): DownloadConfig =
        DownloadConfig(
            defaults = BucketDefaults(
                template = "Shaft/{author} ({author_id})/{title} {id}[?p>1: p{page1}].{ext}",
                storage  = imagesStorage,
            ),
            perBucket = mapOf(
                Bucket.Ugoira to BucketConfig(
                    template = "Shaft/{author} ({author_id})/{title} {id}.gif",
                    storage  = imagesStorage,
                ),
                Bucket.Novel  to BucketConfig(
                    template = "Shaft/{author} ({author_id})/{title} {id}.txt",
                    storage  = downloadsStorage,
                ),
                Bucket.Backup to BucketConfig(template = DefaultTemplates.BACKUP, storage = downloadsStorage),
                Bucket.Log    to BucketConfig(template = DefaultTemplates.LOG,    storage = downloadsStorage),
            ),
        )

    /** 作者 + 年月双层，兼顾「按人找」和「按时间找」。 */
    fun byAuthorAndDate(imagesStorage: StorageChoice, downloadsStorage: StorageChoice): DownloadConfig =
        DownloadConfig(
            defaults = BucketDefaults(
                template = "Shaft/{author} ({author_id})/{created:yyyy-MM}/{title} {id}[?p>1: p{page1}].{ext}",
                storage  = imagesStorage,
            ),
            perBucket = mapOf(
                Bucket.Ugoira to BucketConfig(
                    template = "Shaft/{author} ({author_id})/{created:yyyy-MM}/{title} {id}.gif",
                    storage  = imagesStorage,
                ),
                Bucket.Novel  to BucketConfig(
                    template = "Shaft/Novels/{author} ({author_id})/{created:yyyy-MM}/{title} {id}.txt",
                    storage  = downloadsStorage,
                ),
                Bucket.Backup to BucketConfig(template = DefaultTemplates.BACKUP, storage = downloadsStorage),
                Bucket.Log    to BucketConfig(template = DefaultTemplates.LOG,    storage = downloadsStorage),
            ),
        )

    /** 最短路径——只保留 ID + 扩展名，路径由存储卷自己决定。 */
    fun minimal(imagesStorage: StorageChoice, downloadsStorage: StorageChoice): DownloadConfig =
        DownloadConfig(
            defaults = BucketDefaults(
                template = "Shaft/{id}[?p>1:_p{page1}].{ext}",
                storage  = imagesStorage,
            ),
            perBucket = mapOf(
                Bucket.Ugoira to BucketConfig(template = "Shaft/{id}.gif", storage = imagesStorage),
                Bucket.Novel  to BucketConfig(template = "Shaft/{id}.txt", storage = downloadsStorage),
                Bucket.Backup to BucketConfig(template = DefaultTemplates.BACKUP, storage = downloadsStorage),
                Bucket.Log    to BucketConfig(template = DefaultTemplates.LOG,    storage = downloadsStorage),
            ),
        )

    /** R18 / AI 强制分桶，内部按作者分；适合整理时想「一眼避开 R18」的场景。 */
    fun rFilter(imagesStorage: StorageChoice, downloadsStorage: StorageChoice): DownloadConfig =
        DownloadConfig(
            defaults = BucketDefaults(
                template = "Shaft/[?R18:R18][?!R18:SFW]/[?AI:AI/][?!AI:Human/]{author} ({author_id})/{title} {id}[?p>1: p{page1}].{ext}",
                storage  = imagesStorage,
            ),
            perBucket = mapOf(
                Bucket.Ugoira to BucketConfig(
                    template = "Shaft/[?R18:R18][?!R18:SFW]/[?AI:AI/][?!AI:Human/]{author} ({author_id})/{title} {id}.gif",
                    storage  = imagesStorage,
                ),
                Bucket.Novel  to BucketConfig(
                    template = "Shaft/Novels/[?R18:R18][?!R18:SFW]/{author} ({author_id})/{title} {id}.txt",
                    storage  = downloadsStorage,
                ),
                Bucket.Backup to BucketConfig(template = DefaultTemplates.BACKUP, storage = downloadsStorage),
                Bucket.Log    to BucketConfig(template = DefaultTemplates.LOG,    storage = downloadsStorage),
            ),
        )

    enum class Id {
        ShaftClassic,
        Modern,
        Flat,
        ByDate,
        ByAuthor,
        ByAuthorAndDate,
        Minimal,
        RFilter,
    }

    fun of(
        id: Id,
        imagesStorage: StorageChoice,
        downloadsStorage: StorageChoice,
    ): DownloadConfig = when (id) {
        Id.ShaftClassic    -> shaftClassic(imagesStorage, downloadsStorage)
        Id.Modern          -> modern(imagesStorage, downloadsStorage)
        Id.Flat            -> flat(imagesStorage, downloadsStorage)
        Id.ByDate          -> byDate(imagesStorage, downloadsStorage)
        Id.ByAuthor        -> byAuthor(imagesStorage, downloadsStorage)
        Id.ByAuthorAndDate -> byAuthorAndDate(imagesStorage, downloadsStorage)
        Id.Minimal         -> minimal(imagesStorage, downloadsStorage)
        Id.RFilter         -> rFilter(imagesStorage, downloadsStorage)
    }
}
