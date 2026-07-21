package ceui.pixiv.download

import ceui.pixiv.download.config.BucketConfig
import ceui.pixiv.download.config.BucketDefaults
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.DownloadConfigBackup
import ceui.pixiv.download.config.OverwritePolicy
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Bucket
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 备份还原（#949）的 merge 语义：模板 / 策略 / 开关照抄备份，存储位置只在本机
 * 确实可用时才跟着走。
 */
class DownloadConfigBackupTest {

    private val pictures = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)
    private val downloads = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)

    private val local = DownloadConfig(
        defaults = BucketDefaults(
            template = "local/{id}.{ext}",
            storage = pictures,
            overwrite = OverwritePolicy.Replace,
        ),
        perBucket = mapOf(
            Bucket.Novel to BucketConfig(template = "local-novels/{title}.txt", storage = downloads),
        ),
        wifiOnly = false,
        pageIndexFrom1 = true,
        padPageNumber = false,
    )

    private val backup = DownloadConfig(
        defaults = BucketDefaults(
            template = "backup/{author}/{id}.{ext}",
            storage = downloads,
            overwrite = OverwritePolicy.Skip,
        ),
        perBucket = mapOf(
            Bucket.Novel to BucketConfig(template = "backup-novels/{title}.txt"),
            Bucket.Illust to BucketConfig(overwrite = OverwritePolicy.Rename),
        ),
        wifiOnly = true,
        pageIndexFrom1 = false,
        padPageNumber = true,
    )

    @Test fun `templates, overwrite policy and switches come from the backup`() {
        val merged = DownloadConfigBackup.merge(local, backup) { true }

        assertEquals("backup/{author}/{id}.{ext}", merged.defaults.template)
        assertEquals(OverwritePolicy.Skip, merged.defaults.overwrite)
        assertEquals("backup-novels/{title}.txt", merged.resolve(Bucket.Novel).template)
        assertEquals(OverwritePolicy.Rename, merged.resolve(Bucket.Illust).overwrite)
        assertEquals(true, merged.wifiOnly)
        assertEquals(false, merged.pageIndexFrom1)
        assertEquals(true, merged.padPageNumber)
    }

    @Test fun `usable storage from the backup is restored`() {
        val merged = DownloadConfigBackup.merge(local, backup) { true }
        assertEquals(downloads, merged.defaults.storage)
    }

    @Test fun `unusable storage falls back to the local one`() {
        // 换设备 / 重装后备份里的 SAF treeUri 没有 persistable 权限，照抄会让下载全挂。
        val merged = DownloadConfigBackup.merge(local, backup) { false }

        assertEquals(pictures, merged.defaults.storage)
        // 模板照样还原，只有存储位置留在本机
        assertEquals("backup/{author}/{id}.{ext}", merged.defaults.template)
        assertEquals(downloads, merged.resolve(Bucket.Novel).storage)
    }

    @Test fun `per-bucket entries missing from the backup keep their local values`() {
        val merged = DownloadConfigBackup.merge(local, backup) { true }
        // 备份里 Illust 只覆盖了 overwrite，模板应该继续继承 defaults
        assertEquals("backup/{author}/{id}.{ext}", merged.resolve(Bucket.Illust).template)
    }

    @Test fun `empty backup config leaves local per-bucket overrides alone`() {
        val empty = backup.copy(perBucket = emptyMap())
        val merged = DownloadConfigBackup.merge(local, empty) { true }
        assertEquals("local-novels/{title}.txt", merged.resolve(Bucket.Novel).template)
    }
}
