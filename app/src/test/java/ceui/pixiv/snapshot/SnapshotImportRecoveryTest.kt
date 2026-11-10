package ceui.pixiv.snapshot

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SnapshotImportRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun recover() = SnapshotImportRecovery.recover(temp.root, valid = { dir, _ ->
        File(dir, "complete").isFile
    })

    @Test fun `missing target restores the only valid backup`() {
        snapshot(".backup_my_id_1", "old")
        recover()
        assertEquals("old", File(temp.root, "my_id/complete").readText())
    }

    @Test fun `explicitly committed target permits deleting an obsolete backup`() {
        snapshot("my_id", "new")
        val backup = SnapshotImportRecovery.markCommitted(snapshot(".backup_my_id_1", "old"))
        recover()
        assertEquals("new", File(temp.root, "my_id/complete").readText())
        assertFalse(backup.exists())
    }

    @Test fun `committed backup interrupted while being deleted is still removed`() {
        snapshot("my_id", "new")
        val backup = SnapshotImportRecovery.markCommitted(temp.newFolder(".backup_my_id_1"))
        File(backup, "partial").writeText("half deleted")
        recover()
        assertEquals("new", File(temp.root, "my_id/complete").readText())
        assertFalse(backup.exists())
    }

    @Test fun `committed backup does not resurrect a snapshot deleted afterwards`() {
        val backup = SnapshotImportRecovery.markCommitted(snapshot(".backup_my_id_2", "old"))
        val older = snapshot(".backup_my_id_1", "older")
        recover()
        assertFalse(File(temp.root, "my_id").exists())
        assertFalse(backup.exists())
        assertFalse(older.exists())
    }

    @Test fun `incomplete target is preserved while the old snapshot is recovered`() {
        val partial = temp.newFolder("my_id")
        File(partial, "partial").writeText("unfinished")
        snapshot(".backup_my_id_1", "old")
        recover()
        assertEquals("old", File(temp.root, "my_id/complete").readText())
        val displaced = temp.root.listFiles()!!.single { it.name.startsWith(".incomplete_") }
        assertEquals("unfinished", File(displaced, "partial").readText())
    }

    @Test fun `unreadable backup remains available for manual recovery`() {
        val backup = temp.newFolder(".backup_my_id_1")
        File(backup, "partial").writeText("unknown")
        recover()
        assertTrue(backup.exists())
    }

    @Test fun `newest valid backup wins and repeated recovery is safe`() {
        snapshot(".backup_my_id_1", "older")
        snapshot(".backup_my_id_2", "newer")
        recover(); recover()
        assertEquals("newer", File(temp.root, "my_id/complete").readText())
    }

    private fun snapshot(name: String, text: String) = temp.newFolder(name).also {
        File(it, "complete").writeText(text)
    }
}
