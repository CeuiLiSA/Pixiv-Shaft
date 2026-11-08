package ceui.pixiv.ui.common

import java.io.File
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
