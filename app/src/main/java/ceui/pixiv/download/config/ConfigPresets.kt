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
 */
object ConfigPresets {

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

    fun flat(imagesStorage: StorageChoice, downloadsStorage: StorageChoice): DownloadConfig =
        DownloadConfig(
            defaults = BucketDefaults(
                template = "Shaft/{title} {id}[?p>1: p{page}].{ext}",
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
                template = "Shaft/{created:yyyy}/{created:yyyy-MM}/{title} {id}[?p>1: p{page}].{ext}",
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
                template = "Shaft/{author} ({author_id})/{title} {id}[?p>1: p{page}].{ext}",
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

    enum class Id { ShaftClassic, Flat, ByDate, ByAuthor }

    fun of(
        id: Id,
        imagesStorage: StorageChoice,
        downloadsStorage: StorageChoice,
    ): DownloadConfig = when (id) {
        Id.ShaftClassic -> shaftClassic(imagesStorage, downloadsStorage)
        Id.Flat         -> flat(imagesStorage, downloadsStorage)
        Id.ByDate       -> byDate(imagesStorage, downloadsStorage)
        Id.ByAuthor     -> byAuthor(imagesStorage, downloadsStorage)
    }
}
