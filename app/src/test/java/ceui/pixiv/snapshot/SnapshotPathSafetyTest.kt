package ceui.pixiv.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 快照的路径安全不变式。
 *
 * `.shaftsnap` 是用户从外部导入的 ZIP，manifest / assets / ZIP 条目名全是不可信输入；
 * [requireSnapshotId] / [safeResolve] / [SnapshotArchive.unzip] 是「写不出快照目录」这条
 * 边界的全部实现。这几个函数是纯 JVM 逻辑（只碰 java.io / java.util.zip），值得钉住 ——
 * 一旦哪次重构把 `..` 过滤放宽，静默后果是往 app 私有目录任意位置写文件。
 */
class SnapshotPathSafetyTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---------- requireSnapshotId ----------

    @Test
    fun `accepts uuid style ids`() {
        val id = "0f1e2d3c-4b5a-6978-8765-4321abcdefff"
        assertEquals(id, requireSnapshotId(id))
        assertEquals("A_b-9", requireSnapshotId("A_b-9"))
    }

    @Test
    fun `rejects ids that could escape the snapshot root`() {
        listOf("", "   ", "..", "../other", "a/b", "a.b", "a\\b", "a b").forEach { bad ->
            var threw = false
            try {
                requireSnapshotId(bad)
            } catch (e: SnapshotException) {
                threw = true
            }
            assertTrue("非法快照 ID 应被拒: '$bad'", threw)
        }
    }

    // ---------- safeResolve ----------

    @Test
    fun `resolves paths inside the base dir`() {
        val base = temp.newFolder("snap")
        assertEquals(File(base, "images/p0.jpg").canonicalPath, safeResolve(base, "images/p0.jpg").canonicalPath)
    }

    @Test
    fun `rejects traversal paths`() {
        val base = temp.newFolder("snap")
        listOf("../evil.json", "images/../../evil.json", "../../../../../../tmp/evil").forEach { bad ->
            var threw = false
            try {
                safeResolve(base, bad)
            } catch (e: SnapshotException) {
                threw = true
            }
            assertTrue("越界路径应被拒: '$bad'", threw)
        }
    }

    @Test
    fun `leading slash is contained rather than treated as absolute`() {
        // File(parent, "/etc/hosts") 在 JDK 里始终按 parent 相对解析，所以「绝对路径」形态的
        // rel 不会逃出去，而是落成 <base>/etc/hosts。钉住这个行为：它是安全的那一侧，
        // 但不要误以为 safeResolve 会抛。
        val base = temp.newFolder("snap")
        val resolved = safeResolve(base, "/etc/hosts")
        assertTrue(resolved.canonicalPath.startsWith(base.canonicalPath + File.separator))
    }

    @Test
    fun `sibling dir sharing the base name prefix is not inside`() {
        // baseDir 是 .../snap 时，.../snap_evil 不能被当成「在 snap 里」——
        // 纯前缀比较（没有分隔符那一项）会漏掉这个。
        val base = temp.newFolder("snap")
        var threw = false
        try {
            safeResolve(base, "../snap_evil/x.json")
        } catch (e: SnapshotException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ---------- SnapshotArchive ----------

    @Test
    fun `unzip drops traversal segments instead of writing outside target`() {
        val target = temp.newFolder("out")
        val zip = zipOf(
            "../escaped.txt" to "nope",
            "a/../../escaped2.txt" to "nope",
            "images/p0.jpg" to "ok",
        )
        SnapshotArchive.unzip(ByteArrayInputStream(zip), target)

        assertFalse(File(target.parentFile, "escaped.txt").exists())
        assertFalse(File(target.parentFile, "escaped2.txt").exists())
        // `..` 段被剥掉后条目落在目标目录内，而不是被静默丢弃。
        assertEquals("nope", File(target, "escaped.txt").readText())
        assertEquals("nope", File(target, "a/escaped2.txt").readText())
        assertEquals("ok", File(target, "images/p0.jpg").readText())
    }

    @Test
    fun `unzip normalizes windows separators`() {
        val target = temp.newFolder("out")
        SnapshotArchive.unzip(ByteArrayInputStream(zipOf("images\\p1.jpg" to "ok")), target)
        assertEquals("ok", File(target, "images/p1.jpg").readText())
    }

    @Test
    fun `zip then unzip round trips the directory`() {
        val src = temp.newFolder("src")
        File(src, "manifest.json").writeText("{}")
        File(src, "images").mkdirs()
        File(src, "images/p0.jpg").writeText("bytes")

        val out = ByteArrayOutputStream()
        SnapshotArchive.zipDirectory(src, out)
        val restored = temp.newFolder("restored")
        SnapshotArchive.unzip(ByteArrayInputStream(out.toByteArray()), restored)

        assertEquals("{}", File(restored, "manifest.json").readText())
        assertEquals("bytes", File(restored, "images/p0.jpg").readText())
    }

    // ---------- 文件名 / 扩展名 ----------

    @Test
    fun `export file name strips characters illegal on saf targets`() {
        val manifest = SnapshotManifest(snapshotId = "abc", illustId = 123L, title = "a/b:c*?\"<>|d")
        assertEquals("a_b_c______d_123$SNAPSHOT_EXTENSION", manifest.safeExportFileName())
    }

    @Test
    fun `export file name falls back when title is blank`() {
        val manifest = SnapshotManifest(snapshotId = "abc", illustId = 7L, title = "   ")
        assertEquals("snapshot_7$SNAPSHOT_EXTENSION", manifest.safeExportFileName())
    }

    @Test
    fun `extension comes from the path segment not the query string`() {
        assertEquals(".jpg", "https://i.pximg.net/img/1_p0.jpg".snapshotExtension())
        assertEquals(".png", "https://i.pximg.net/img/1_p0.png?v=2".snapshotExtension())
        assertEquals(".img", "https://i.pximg.net/img/noextension".snapshotExtension())
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
