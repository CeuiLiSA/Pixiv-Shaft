package ceui.pixiv.snapshot

import ceui.lisa.activities.Shaft
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SnapshotIndexTest {
    @get:Rule val temp = TemporaryFolder()

    @Before fun setUp() {
        Shaft.sGson = Gson()
    }

    private fun record(id: String, illustId: Long = 42, auto: Boolean = false, time: Long = 1) =
        SnapshotIndexRecord(illustId, SnapshotIndex.Entry(id, auto, time))

    @Test fun `deleting preferred snapshot falls back through all remaining candidates`() {
        val state = SnapshotIndexState()
        state.finishLoading(emptyMap())
        state.write(record("auto", auto = true, time = 30))
        state.write(record("old", time = 10))
        state.write(record("new", time = 20))
        assertEquals("new", state.preferredByIllust()[42]?.snapshotId)
        state.delete("new")
        assertEquals("old", state.preferredByIllust()[42]?.snapshotId)
        state.delete("old")
        assertEquals("auto", state.preferredByIllust()[42]?.snapshotId)
        state.delete("auto")
        assertTrue(state.preferredByIllust().isEmpty())
    }

    @Test fun `startup write preserves unrelated scanned snapshots`() {
        val state = SnapshotIndexState()
        val old = record("old", illustId = 1)
        val new = record("new", illustId = 2)
        state.write(new)
        assertFalse(state.loaded)
        assertTrue(state.finishLoading(mapOf("old" to old)))
        assertEquals(setOf(1L, 2L), state.preferredByIllust().keys)
        assertTrue(state.loaded)
    }

    @Test fun `deletion before first scan publication cannot resurrect a snapshot`() {
        val state = SnapshotIndexState()
        val deleted = record("deleted")
        state.delete("deleted")
        state.finishLoading(mapOf("deleted" to deleted))
        assertTrue(state.records.isEmpty())
    }

    @Test fun `startup promotion overrides scanned auto metadata`() {
        val state = SnapshotIndexState()
        state.write(record("auto", time = 10))
        state.finishLoading(mapOf("auto" to record("auto", auto = true, time = 10)))
        assertFalse(state.preferredByIllust().getValue(42).isAuto)
    }

    @Test fun `startup delete and reimport obey the last operation`() {
        val state = SnapshotIndexState()
        state.delete("same")
        state.write(record("same", illustId = 2))
        state.finishLoading(mapOf("same" to record("same", illustId = 1)))
        assertNull(state.preferredByIllust()[1])
        assertEquals("same", state.preferredByIllust()[2]?.snapshotId)
    }

    @Test fun `importing older archive does not replace newer creation`() {
        val state = SnapshotIndexState()
        state.finishLoading(emptyMap())
        state.write(record("newer", time = 200))
        state.write(record("imported-older", time = 100))
        assertEquals("newer", state.preferredByIllust()[42]?.snapshotId)
        val restarted = SnapshotIndexState()
        restarted.finishLoading(state.records)
        assertEquals(state.preferredByIllust(), restarted.preferredByIllust())
    }

    @Test fun `promotion of nonpreferred auto candidate can become preferred formal`() {
        val state = SnapshotIndexState()
        state.finishLoading(emptyMap())
        state.write(record("formal", time = 10))
        state.write(record("auto", auto = true, time = 20))
        state.write(record("auto", time = 20))
        assertEquals("auto", state.preferredByIllust()[42]?.snapshotId)
        assertFalse(state.preferredByIllust().getValue(42).isAuto)
    }

    @Test fun `replacing same snapshot id cannot retain another illust mapping`() {
        val state = SnapshotIndexState()
        state.finishLoading(emptyMap())
        state.write(record("same", illustId = 1))
        state.write(record("same", illustId = 2))
        assertEquals(setOf(2L), state.preferredByIllust().keys)
    }

    @Test fun `cold scan persists all duplicates and stable startup does not parse known manifests`() {
        writeFormal("old", 10)
        writeFormal("new", 20)
        val scanned = scanSnapshotIndex(temp.root, emptyMap())
        assertEquals(setOf("old", "new"), scanned.keys)
        // 已知目录只核对存在性。破坏内容但保留文件，若又解析就会丢掉该行。
        File(temp.root, "old/$SNAPSHOT_MANIFEST").writeText("not json")
        File(temp.root, "new/$SNAPSHOT_MANIFEST").writeText("not json")
        assertEquals(scanned, scanSnapshotIndex(temp.root, scanned))
    }

    @Test fun `cold scan detects promotion even when old auto manifest remains`() {
        val dir = temp.newFolder("promoted")
        File(dir, AUTO_SNAPSHOT_MANIFEST).writeText(Shaft.sGson.toJson(
            AutoSnapshotManifest(snapshotId = "promoted", illustId = 42, createdAt = 10),
        ))
        val auto = scanSnapshotIndex(temp.root, emptyMap())
        File(dir, SNAPSHOT_MANIFEST).writeText(Shaft.sGson.toJson(
            SnapshotManifest(snapshotId = "promoted", illustId = 42, createdAt = 10),
        ))
        val promoted = scanSnapshotIndex(temp.root, auto)
        assertFalse(promoted.getValue("promoted").entry.isAuto)
    }

    @Test fun `cold scan drops deleted directories and rejects backup or corrupt manifests`() {
        writeFormal("valid", 10)
        val scanned = scanSnapshotIndex(temp.root, emptyMap())
        File(temp.root, "valid").deleteRecursively()
        val backup = temp.newFolder(".backup_valid")
        File(backup, SNAPSHOT_MANIFEST).writeText(Shaft.sGson.toJson(
            SnapshotManifest(snapshotId = "valid", illustId = 42),
        ))
        val broken = temp.newFolder("broken")
        File(broken, SNAPSHOT_MANIFEST).writeText("broken")
        assertTrue(scanSnapshotIndex(temp.root, scanned).isEmpty())
    }

    private fun writeFormal(id: String, time: Long) {
        val dir = temp.newFolder(id)
        File(dir, SNAPSHOT_MANIFEST).writeText(Shaft.sGson.toJson(
            SnapshotManifest(snapshotId = id, illustId = 42, createdAt = time),
        ))
    }
}
