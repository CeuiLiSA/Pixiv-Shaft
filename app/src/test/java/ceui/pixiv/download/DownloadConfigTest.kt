package ceui.pixiv.download

import ceui.pixiv.download.config.BucketConfig
import ceui.pixiv.download.config.BucketDefaults
import ceui.pixiv.download.config.ConfigPresets
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.OverwritePolicy
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Bucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadConfigTest {

    private val defaults = BucketDefaults(
        template = "default/{id}.{ext}",
        storage = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images),
    )

    @Test fun `resolve falls back to defaults when no override`() {
        val cfg = DownloadConfig(defaults = defaults)
        val r = cfg.resolve(Bucket.Illust)
        assertEquals("default/{id}.{ext}", r.template)
        assertEquals(defaults.storage, r.storage)
        assertEquals(OverwritePolicy.Replace, r.overwrite)
    }

    @Test fun `per-bucket override wins for specified fields only`() {
        val cfg = DownloadConfig(
            defaults = defaults,
            perBucket = mapOf(
                Bucket.Novel to BucketConfig(
                    template = "novels/{title}.txt",
                    overwrite = OverwritePolicy.Skip,
                ),
            ),
        )
        val r = cfg.resolve(Bucket.Novel)
        assertEquals("novels/{title}.txt", r.template)
        assertEquals(OverwritePolicy.Skip, r.overwrite)
        assertEquals(defaults.storage, r.storage)   // inherited
    }

    @Test fun `withBucket replaces override immutably`() {
        val cfg = DownloadConfig(defaults = defaults)
        val next = cfg.withBucket(Bucket.Ugoira, BucketConfig(template = "ugoira/{id}.gif"))
        assertEquals(1, next.perBucket.size)
        assertEquals("ugoira/{id}.gif", next.resolve(Bucket.Ugoira).template)
        assertEquals(0, cfg.perBucket.size)   // original untouched
    }

    @Test fun `withoutOverride removes a bucket entry`() {
        val cfg = DownloadConfig(
            defaults = defaults,
            perBucket = mapOf(Bucket.Illust to BucketConfig(template = "x")),
        )
        val next = cfg.withoutOverride(Bucket.Illust)
        assertEquals("default/{id}.{ext}", next.resolve(Bucket.Illust).template)
    }

    // ── Global OverwritePolicy ──

    @Test fun `changing defaults overwrite affects all buckets without per-bucket override`() {
        val cfg = DownloadConfig(
            defaults = defaults.copy(overwrite = OverwritePolicy.Skip),
        )
        assertEquals(OverwritePolicy.Skip, cfg.resolve(Bucket.Illust).overwrite)
        assertEquals(OverwritePolicy.Skip, cfg.resolve(Bucket.Novel).overwrite)
        assertEquals(OverwritePolicy.Skip, cfg.resolve(Bucket.Ugoira).overwrite)
    }

    @Test fun `default overwrite is Replace`() {
        assertEquals(OverwritePolicy.Replace, defaults.overwrite)
    }

    // ── Global StorageChoice via applyGlobalStorage logic ──

    @Test fun `MediaStore global storage routes images to Pictures and downloads to Downloads`() {
        val images = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)
        val downloads = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)
        val cfg = ConfigPresets.shaftClassic(images, downloads)

        assertEquals(images, cfg.resolve(Bucket.Illust).storage)
        assertEquals(images, cfg.resolve(Bucket.Ugoira).storage)
        assertEquals(downloads, cfg.resolve(Bucket.Novel).storage)
        assertEquals(downloads, cfg.resolve(Bucket.Backup).storage)
        assertEquals(downloads, cfg.resolve(Bucket.Log).storage)
    }

    @Test fun `switching storage preserves templates`() {
        val images = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)
        val downloads = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)
        val newStorage = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)
        val original = ConfigPresets.shaftClassic(images, downloads)

        // Simulate applyGlobalStorage logic: replace storage, keep templates
        val switched = original.copy(
            defaults = original.defaults.copy(storage = newStorage),
            perBucket = original.perBucket.mapValues { (_, bc) ->
                bc.copy(storage = newStorage)
            },
        )

        // Templates preserved
        assertEquals(original.resolve(Bucket.Illust).template, switched.resolve(Bucket.Illust).template)
        assertEquals(original.resolve(Bucket.Novel).template, switched.resolve(Bucket.Novel).template)
        assertEquals(original.resolve(Bucket.Ugoira).template, switched.resolve(Bucket.Ugoira).template)

        // Storage switched
        assertEquals(newStorage, switched.resolve(Bucket.Illust).storage)
        assertEquals(newStorage, switched.resolve(Bucket.Novel).storage)
    }

    @Test fun `all presets produce valid configs for every user bucket`() {
        val images = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)
        val downloads = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)
        val userBuckets = listOf(Bucket.Illust, Bucket.Ugoira, Bucket.Novel, Bucket.Backup, Bucket.Log)
        for (id in ConfigPresets.Id.entries) {
            val cfg = ConfigPresets.of(id, images, downloads)
            for (bucket in userBuckets) {
                val r = cfg.resolve(bucket)
                assertTrue("Preset $id bucket $bucket template blank", r.template.isNotBlank())
            }
        }
    }
}
