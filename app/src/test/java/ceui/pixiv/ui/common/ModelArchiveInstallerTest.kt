package ceui.pixiv.ui.common

import java.io.File
import java.util.concurrent.CancellationException
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

    @Test fun `complete package installs with no previous model`() {
        val dir = File(temp.root, "model")
        assertTrue(ModelArchiveInstaller.install(zip(required.associateWith { "new" }), dir, required))
        assertTrue(ModelArchiveInstaller.isReady(dir, required))
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

    @Test fun `cancel during extraction leaves the installed model intact`() {
        val dir = installed()
        var checks = 0
        try {
            ModelArchiveInstaller.install(
                zip(mapOf("encoder.onnx" to "new".repeat(10000), "decoder.onnx" to "new")),
                dir,
                required,
            ) {
                if (++checks >= 4) throw CancellationException("cancelled")
            }
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        required.forEach { assertEquals("old", File(dir, it).readText()) }
        assertFalse(File(temp.root, "model.staging").exists())
    }

    @Test fun `fixed staging is cleared before rejecting a bad zip`() {
        val dir = installed()
        val staging = temp.newFolder("model.staging")
        File(staging, "partial.onnx").writeText("unfinished")
        val badZip = temp.newFile().also { it.writeText("not a zip") }
        assertFalse(ModelArchiveInstaller.install(badZip, dir, required))
        assertFalse(staging.exists())
        required.forEach { assertEquals("old", File(dir, it).readText()) }
    }

    @Test fun `fixed cache paths clear interrupted archives`() {
        val cache = temp.newFolder("cache")
        val dir = installed()
        val staging = temp.newFolder("model.staging")
        val download = File(cache, "model_dl_model.zip").also { it.writeText("old") }
        val imported = File(cache, "model_import_model.zip").also { it.writeText("old") }
        val unrelated = File(cache, "model_dl_other.zip").also { it.writeText("keep") }
        val archive = ModelArchiveInstaller.prepareTempArchive(cache, dir, "model")
        assertEquals(download, archive)
        assertFalse(download.exists())
        assertFalse(imported.exists())
        assertFalse(staging.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun `deleting a model removes fixed recovery artifacts`() {
        val dir = installed()
        val cache = temp.newFolder("cache")
        val backup = temp.newFolder("model.backup")
        val staging = temp.newFolder("model.staging")
        val download = File(cache, "model_dl_model.zip").also { it.writeText("old") }
        val imported = File(cache, "model_import_model.zip").also { it.writeText("old") }
        ModelArchiveInstaller.delete(dir, cache, "model")
        listOf(dir, backup, staging, download, imported).forEach { assertFalse(it.exists()) }
    }

    @Test fun `ready check restores a model left in backup`() {
        val dir = installed()
        val backup = File(temp.root, "model.backup")
        assertTrue(dir.renameTo(backup))
        assertTrue(ModelArchiveInstaller.recoverReady(dir, required))
        required.forEach { assertEquals("old", File(dir, it).readText()) }
        assertFalse(backup.exists())
    }

    @Test fun `ready check removes backup after a completed replacement`() {
        val dir = installed()
        val backup = temp.newFolder("model.backup")
        required.forEach { File(backup, it).writeText("older") }
        assertTrue(ModelArchiveInstaller.recoverReady(dir, required))
        assertFalse(backup.exists())
        required.forEach { assertEquals("old", File(dir, it).readText()) }
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
