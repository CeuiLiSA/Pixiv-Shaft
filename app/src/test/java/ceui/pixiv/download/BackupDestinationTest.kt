package ceui.pixiv.download

import ceui.pixiv.download.config.BucketConfig
import ceui.pixiv.download.config.BucketDefaults
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Bucket
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupDestinationTest {

    private val storage = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)

    private fun config(backupTemplate: String) = DownloadConfig(
        defaults = BucketDefaults(template = "default/{id}.{ext}", storage = storage),
        perBucket = mapOf(Bucket.Backup to BucketConfig(template = backupTemplate)),
    )

    @Test fun `backup destination uses full Backup bucket template path`() {
        val path = DownloadItems.backupDestination(
            config("ShaftBackups/Shaft-Backup.json"),
        )
        assertEquals(listOf("ShaftBackups", "Shaft-Backup.json"), path.segments)
    }

    @Test fun `backup destination uses template filename instead of caller name`() {
        val path = DownloadItems.backupDestination(
            config("MyBackups/MyBackup.json"),
        )
        assertEquals(listOf("MyBackups", "MyBackup.json"), path.segments)
    }
}