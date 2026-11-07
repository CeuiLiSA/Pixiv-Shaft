package ceui.lisa.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkDownloadManagerZipTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createZip(vararg entryNames: String): File {
        val file = tempFolder.newFile()
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            for (name in entryNames) {
                zos.putNextEntry(ZipEntry(name))
                zos.write("dummy-content".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `validateZip returns false for null or nonexistent file`() {
        assertFalse(ApkDownloadManager.validateZip(null))
        assertFalse(ApkDownloadManager.validateZip(File(tempFolder.root, "not_exist.apk")))
    }

    @Test
    fun `validateZip returns false for empty or too short file`() {
        val emptyFile = tempFolder.newFile("empty.apk")
        assertFalse(ApkDownloadManager.validateZip(emptyFile))

        val shortFile = tempFolder.newFile("short.apk").apply {
            writeBytes(ByteArray(15) { 0x50 })
        }
        assertFalse(ApkDownloadManager.validateZip(shortFile))
    }

    @Test
    fun `validateZip returns false for random non-zip bytes`() {
        val randomFile = tempFolder.newFile("random.apk").apply {
            writeBytes(ByteArray(1024) { 0xAB.toByte() })
        }
        assertFalse(ApkDownloadManager.validateZip(randomFile))
    }

    @Test
    fun `validateZip returns false for truncated zip`() {
        val validZip = createZip("AndroidManifest.xml")
        val bytes = validZip.readBytes()
        val truncatedFile = tempFolder.newFile("truncated.apk").apply {
            writeBytes(bytes.copyOf(bytes.size / 2))
        }
        assertFalse(ApkDownloadManager.validateZip(truncatedFile))
    }

    @Test
    fun `validateZip returns false for empty zip without entries`() {
        val emptyZip = tempFolder.newFile("empty_zip.apk")
        ZipOutputStream(FileOutputStream(emptyZip)).use {
            // zero entries written
        }
        assertFalse(ApkDownloadManager.validateZip(emptyZip))
    }

    @Test
    fun `validateZip returns false for zip without AndroidManifest_xml`() {
        val nonApkZip = createZip("classes.dex", "res/values/strings.xml")
        assertFalse(ApkDownloadManager.validateZip(nonApkZip))
    }

    @Test
    fun `validateZip returns true for valid apk zip with AndroidManifest_xml`() {
        val validApkZip = createZip("AndroidManifest.xml", "classes.dex")
        assertTrue(ApkDownloadManager.validateZip(validApkZip))
    }
}
