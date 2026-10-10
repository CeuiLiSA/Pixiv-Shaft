package ceui.pixiv.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavBackupFilesTest {

    private val deviceA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val deviceB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val time = 1_791_531_012_000L

    private fun entry(timeMs: Long, deviceId: String, model: String = "Pixel-8") = WebDavEntry(
        WebDavBackup.newFileName(timeMs, model, deviceId), 1234L,
    )

    @Test
    fun `same-model devices cannot overwrite each others backup at the same time`() {
        val first = entry(time, deviceA)
        val second = entry(time, deviceB)
        assertNotEquals(first.name, second.name)
        assertEquals("Pixel-8", WebDavBackup.parse(first)?.device)
        assertEquals(deviceA, WebDavBackup.parse(first)?.deviceId)
        assertEquals(deviceB, WebDavBackup.parse(second)?.deviceId)
    }

    @Test
    fun `backups within one second retain distinct names and exact timestamps`() {
        val first = entry(time + 1L, deviceA)
        val second = entry(time + 2L, deviceA)
        assertNotEquals(first.name, second.name)
        assertEquals(time + 1L, WebDavBackup.parse(first)?.timeMs)
        assertEquals(time + 2L, WebDavBackup.parse(second)?.timeMs)
    }

    @Test
    fun `pruning only removes this installations oldest backups`() {
        val own = (0..11).map { entry(time + it * 1_000L, deviceA) }
        val other = (0..11).map { entry(time + it * 1_000L, deviceB) }
        val legacy = WebDavEntry("Shaft-Backup_20261009T073012Z_Pixel-8.json.gz", 456L)
        val stale = WebDavBackup.staleBackups((own + other + legacy).reversed(), deviceA)
        assertEquals(setOf(own[0].name, own[1].name), stale.map { it.name }.toSet())
        assertTrue(stale.all { it.deviceId == deviceA })
    }

    @Test
    fun `installation quota is shared across model label changes`() {
        val own = (0..10).map { entry(time + it * 1_000L, deviceA, "model-$it") }
        assertEquals(listOf(own.first().name), WebDavBackup.staleBackups(own, deviceA).map { it.name })
    }

    @Test
    fun `legacy names remain available for restore without claiming ownership`() {
        val legacy = WebDavEntry("Shaft-Backup_20261009T073012Z_Pixel-8.json.gz", 456L)
        val parsed = WebDavBackup.parse(legacy)
        assertEquals("Pixel-8", parsed?.device)
        assertNull(parsed?.deviceId)
        assertEquals(456L, parsed?.size)
    }

    @Test
    fun `unrelated files and invalid calendar timestamps are ignored`() {
        assertNull(WebDavBackup.parse(WebDavEntry("readme.txt", 0L)))
        assertNull(WebDavBackup.parse(WebDavEntry("Shaft-Backup_20260230T073012Z_Pixel-8.json.gz", 0L)))
    }
}
