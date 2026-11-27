package ceui.lisa.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Size queries must use [File.length], never [java.io.InputStream.available],
 * and must not create a missing file as a side effect of asking how big it is.
 */
class FileSizeUtilTest {

    @Test
    fun `missing file is reported as zero and is not created`() {
        val dir = Files.createTempDirectory("shaft-filesize").toFile()
        try {
            val missing = File(dir, "does-not-exist.bin")
            assertFalse(missing.exists())
            assertEquals(0.0, FileSizeUtil.getFileOrFilesSize(missing, FileSizeUtil.SIZETYPE_B), 0.0)
            assertEquals(0.0, FileSizeUtil.getFileOrFilesSize(missing.path, FileSizeUtil.SIZETYPE_B), 0.0)
            assertEquals("0B", FileSizeUtil.getAutoFileOrFilesSize(missing.path))
            assertFalse("querying size must not create the file", missing.exists())
            assertEquals(0, dir.list()?.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `existing file size matches File length`() {
        val file = File.createTempFile("shaft-filesize", ".bin")
        try {
            val payload = ByteArray(4096) { 1 }
            file.writeBytes(payload)
            assertEquals(
                payload.size.toDouble(),
                FileSizeUtil.getFileOrFilesSize(file, FileSizeUtil.SIZETYPE_B),
                0.0,
            )
            assertEquals(
                payload.size.toDouble(),
                FileSizeUtil.getFileOrFilesSize(file.path, FileSizeUtil.SIZETYPE_B),
                0.0,
            )
            assertEquals(FileSizeUtil.formatFileSize(payload.size.toLong()), FileSizeUtil.getAutoFileOrFilesSize(file.path))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `directory size is the sum of children and does not create files`() {
        val dir = Files.createTempDirectory("shaft-filesize-dir").toFile()
        try {
            File(dir, "a.bin").writeBytes(ByteArray(100))
            File(dir, "sub").mkdirs()
            File(dir, "sub/b.bin").writeBytes(ByteArray(250))
            val before = dir.walkTopDown().map { it.relativeTo(dir).path to it.isFile }.toSet()
            assertEquals(
                350.0,
                FileSizeUtil.getFileOrFilesSize(dir, FileSizeUtil.SIZETYPE_B),
                0.0,
            )
            val after = dir.walkTopDown().map { it.relativeTo(dir).path to it.isFile }.toSet()
            assertEquals("directory size must not add or create files", before, after)
        } finally {
            dir.deleteRecursively()
        }
    }
}
