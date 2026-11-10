package ceui.lisa.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkDownloadManagerZipTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createZip(vararg entryNames: String, method: Int = ZipEntry.DEFLATED): File {
        val file = tempFolder.newFile()
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            for (name in entryNames) {
                val content = "dummy-content:$name".toByteArray(Charsets.UTF_8)
                zos.putNextEntry(ZipEntry(name).apply {
                    this.method = method
                    if (method == ZipEntry.STORED) {
                        size = content.size.toLong()
                        crc = CRC32().apply { update(content) }.value
                    }
                })
                zos.write(content)
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

    @Test
    fun `validateZip returns true for valid stored entries`() {
        val file = createZip("AndroidManifest.xml", "classes.dex", method = ZipEntry.STORED)
        assertTrue(ApkDownloadManager.validateZip(file))
    }

    @Test
    fun `validateZip rejects same length corruption outside the manifest`() {
        val file = createZip("AndroidManifest.xml", "classes.dex", method = ZipEntry.STORED)
        val bytes = file.readBytes()
        val offset = bytes.toString(Charsets.ISO_8859_1).indexOf("dummy-content:classes.dex")
        assertTrue(offset >= 0)
        bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
        file.writeBytes(bytes)

        assertFalse(ApkDownloadManager.validateZip(file))
    }

    @Test
    fun `validateZip rejects unreadable compressed entry with intact directory`() {
        val file = createZip("AndroidManifest.xml", "classes.dex")
        val bytes = file.readBytes()
        val name = "classes.dex"
        // ZipOutputStream writes no local extra field for this entry, so data follows its name.
        val offset = bytes.toString(Charsets.ISO_8859_1).indexOf(name) + name.length
        assertTrue(offset >= name.length)
        bytes[offset] = 0x07 // Invalid DEFLATE block type (BTYPE=3).
        file.writeBytes(bytes)

        assertFalse(ApkDownloadManager.validateZip(file))
    }

    @Test
    fun `validateZip rejects an empty manifest`() {
        val file = tempFolder.newFile()
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.closeEntry()
        }

        assertFalse(ApkDownloadManager.validateZip(file))
    }

    @Test
    fun `validateZip rejects a directory named as the manifest`() {
        assertFalse(ApkDownloadManager.validateZip(createZip("AndroidManifest.xml/")))
    }
}
