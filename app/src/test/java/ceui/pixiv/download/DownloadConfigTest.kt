package ceui.pixiv.download

import ceui.pixiv.download.config.BucketConfig
import ceui.pixiv.download.config.BucketDefaults
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.OverwritePolicy
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Bucket
import org.junit.Assert.assertEquals
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
        assertEquals(OverwritePolicy.Rename, r.overwrite)
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
}
