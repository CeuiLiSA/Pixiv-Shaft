package ceui.pixiv.download

import ceui.pixiv.download.config.BucketConfig
import ceui.pixiv.download.config.BucketDefaults
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.template.DefaultTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDestinationTest {

    private val storage = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Downloads)

    private fun config(backupTemplate: String) = DownloadConfig(
        defaults = BucketDefaults(template = "default/{id}.{ext}", storage = storage),
        perBucket = mapOf(Bucket.Backup to BucketConfig(template = backupTemplate)),
    )

    @Test fun `backup destination uses full Backup bucket template path`() {
        val path = DownloadItems.backupDestination(
            "Shaft-Backup.json",
            config("ShaftBackups/Shaft-Backup.json"),
        )
        assertEquals(listOf("ShaftBackups", "Shaft-Backup.json"), path.segments)
    }

    @Test fun `backup destination uses template filename instead of caller name`() {
        val path = DownloadItems.backupDestination(
            "Shaft-BrowseHistory.json",
            config("MyBackups/MyBackup.json"),
        )
        assertEquals(listOf("MyBackups", "MyBackup.json"), path.segments)
    }

    @Test fun `caller file name is exposed to the template as title`() {
        // 设置备份 / 浏览历史 / 屏蔽记录 / 词典四种导出共用 Backup 桶，默认模板靠 {title}
        // 把它们区分开，否则全都叫 Shaft-Backup_<时间>.json。
        val history = DownloadItems.backupDestination(
            "Shaft-BrowseHistory.json",
            config("ShaftBackups/{title}_{created:yyyyMMdd}.json"),
        )
        assertEquals("ShaftBackups", history.directory.single())
        assertTrue(history.filename, history.filename.startsWith("Shaft-BrowseHistory_"))
        assertTrue(history.filename, history.filename.endsWith(".json"))

        val mute = DownloadItems.backupDestination(
            "Shaft-MuteRecords.json",
            config(DefaultTemplates.BACKUP),
        )
        assertTrue(mute.filename, mute.filename.startsWith("Shaft-MuteRecords_"))
    }

    @Test fun `backup falls back to its own default template when perBucket is empty`() {
        // 设置页「全部重置」会把 perBucket 清空；备份不能掉到 defaults 的插画模板。
        val cfg = DownloadConfig(
            defaults = BucketDefaults(template = "ShaftImages/{title}_{id}.{ext}", storage = storage),
        )
        val path = DownloadItems.backupDestination("Shaft-Backup.json", cfg)
        assertEquals("ShaftBackups", path.directory.single())
        assertTrue(path.filename, path.filename.startsWith("Shaft-Backup_"))
    }
}
