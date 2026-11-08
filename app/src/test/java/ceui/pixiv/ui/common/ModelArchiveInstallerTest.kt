package ceui.pixiv.ui.common

import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelArchiveInstallerTest {
    @get:Rule val temp = TemporaryFolder()
    private val required = listOf("encoder.onnx", "decoder.onnx")

    @Test fun `incomplete package preserves the installed model`() {
        val dir = installed()
        assertFalse(ModelArchiveInstaller.install(zip(mapOf("encoder.onnx" to "new")), dir, required))
        required.forEach { assertEquals("old", File(dir, it).readText()) }
    }

    @Test fun `empty required files are rejected`() {
        val dir = File(temp.root, "model")
        assertFalse(ModelArchiveInstaller.install(zip(required.associateWith { "" }), dir, required))
        assertFalse(ModelArchiveInstaller.isReady(dir, required))
        assertFalse(dir.exists())
    }

    @Test fun `complete package replaces the model`() {
        val dir = installed()
        assertTrue(ModelArchiveInstaller.install(zip(required.associateWith { "new" }), dir, required))
        required.forEach { assertEquals("new", File(dir, it).readText()) }
        assertFalse(File(temp.root, "model.backup").exists())
    }

    @Test fun `rejected package recovers a previous interrupted installation`() {
        val dir = installed()
        assertTrue(dir.renameTo(File(temp.root, "model.backup")))
        assertFalse(ModelArchiveInstaller.install(zip(mapOf("other" to "bad")), dir, required))
        required.forEach { assertEquals("old", File(dir, it).readText()) }
    }

    @Test fun `traversal is rejected without changing the installed model`() {
        val dir = installed()
        try {
            ModelArchiveInstaller.install(zip(mapOf("../escaped" to "bad")), dir, required)
            fail("expected traversal rejection")
        } catch (_: SecurityException) { }
        required.forEach { assertEquals("old", File(dir, it).readText()) }
        assertFalse(File(temp.root, "escaped").exists())
    }

    @Test fun `repeated interrupted extraction is cleaned even before rejecting a bad zip`() {
        val dir = installed()
        val backup = temp.newFolder("model.backup")
        val otherModel = temp.newFolder("other.staging-${UUID.randomUUID()}")
        val unrelated = temp.newFolder("model.staging-custom")
        val badZip = temp.newFile().also { it.writeText("not a zip") }
        repeat(3) {
            val orphan = temp.newFolder("model.staging-${UUID.randomUUID()}")
            File(orphan, "partial.onnx").writeText("unfinished")
            val legacy = temp.newFolder("model.staging")
            assertFalse(ModelArchiveInstaller.install(badZip, dir, required))
            assertFalse(orphan.exists())
            assertFalse(legacy.exists())
        }
        required.forEach { assertEquals("old", File(dir, it).readText()) }
        assertTrue(backup.exists())
        assertTrue(otherModel.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun `cache sweep removes orphan zips and preserves concurrent download and import`() {
        val cache = temp.newFolder("cache")
        val dir = installed()
        val staging = temp.newFolder("model.staging-${UUID.randomUUID()}")
        val stale = listOf(
            "model_dl_model_${UUID.randomUUID()}.zip", "model_import_model_${UUID.randomUUID()}.zip",
            "model_dl_model.zip", "model_import_model.zip",
        ).map { File(cache, it).also { file -> file.writeText("orphan") } }
        val other = File(cache, "model_dl_other_${UUID.randomUUID()}.zip").also { it.writeText("other") }
        val unrelated = File(cache, "model_dl_model_custom.zip").also { it.writeText("keep") }
        val download = ModelArchiveInstaller.createTempArchive(cache, dir, "model")
        try {
            download.writeText("downloading")
            val imported = ModelArchiveInstaller.createTempArchive(cache, dir, "model", importing = true)
            try {
                imported.writeText("importing")
                stale.forEach { assertFalse(it.exists()) }
                assertFalse(staging.exists())
                assertNotEquals(download, imported)
                assertEquals("downloading", download.readText())
                assertEquals("importing", imported.readText())
                assertTrue(other.exists())
                assertTrue(unrelated.exists())
            } finally {
                ModelArchiveInstaller.releaseTempArchive(imported)
            }
        } finally {
            ModelArchiveInstaller.releaseTempArchive(download)
        }
        assertFalse(download.exists())
    }

    @Test fun `deleting a model removes recovery artifacts without deleting an active archive`() {
        val dir = installed()
        val cache = temp.newFolder("cache")
        val active = ModelArchiveInstaller.createTempArchive(cache, dir, "model")
        try {
            active.writeText("downloading")
            val backup = temp.newFolder("model.backup")
            val staging = temp.newFolder("model.staging-${UUID.randomUUID()}")
            val legacy = temp.newFolder("model.staging")
            val orphan = File(cache, "model_import_model_${UUID.randomUUID()}.zip").also { it.writeText("orphan") }
            val other = temp.newFolder("other")
            ModelArchiveInstaller.delete(dir, cache, "model")
            listOf(dir, backup, staging, legacy, orphan).forEach { assertFalse(it.exists()) }
            assertTrue(other.exists())
            assertEquals("downloading", active.readText())
        } finally {
            ModelArchiveInstaller.releaseTempArchive(active)
        }
    }

    private fun installed(): File = temp.newFolder("model").also { dir ->
        required.forEach { File(dir, it).writeText("old") }
    }

    private fun zip(entries: Map<String, String>): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry()
            }
        }
    }
}
