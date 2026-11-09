package ceui.pixiv.download

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ImageIntegrityUriRepairTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `seekable content repair changes only the damaged byte without truncating the file`() {
        val (healthy, damaged) = repairablePngBytes()
        val file = folder.newFile().apply { writeBytes(damaged) }
        val uri = contentFile(file)

        assertTrue(ImageIntegrityValidator.repairUriIfPossible(RuntimeEnvironment.getApplication(), uri))

        assertArrayEquals(healthy, file.readBytes())
        assertFalse(RepairProvider.modes.any { it.contains('t') })
    }

    @Test
    fun `a sequential provider with a failing output cannot truncate the original during repair`() {
        val (_, damaged) = repairablePngBytes()
        val file = folder.newFile().apply { writeBytes(damaged) }
        val uri = contentFile(file)
        RepairProvider.sequentialOnly = true
        shadowOf(RuntimeEnvironment.getApplication().contentResolver).registerOutputStreamSupplier(uri) {
            file.writeBytes(ByteArray(0)) // Opening rwt truncates before the first write.
            object : OutputStream() {
                override fun write(value: Int) { throw IOException("provider write failed") }
            }
        }

        assertFalse(ImageIntegrityValidator.repairUriIfPossible(RuntimeEnvironment.getApplication(), uri))

        assertArrayEquals("failed self-healing must preserve the recoverable original", damaged, file.readBytes())
    }

    @Test
    fun `content changed after validation is preserved instead of overwritten`() {
        val (_, damaged) = repairablePngBytes()
        val file = folder.newFile().apply { writeBytes(damaged) }
        val uri = contentFile(file)
        val replacement = ByteArray(damaged.size) { 0x11 }
        RepairProvider.beforeWriteOpen = { file.writeBytes(replacement) }

        assertFalse(ImageIntegrityValidator.repairUriIfPossible(RuntimeEnvironment.getApplication(), uri))

        assertArrayEquals(replacement, file.readBytes())
    }

    private fun contentFile(file: File): Uri {
        RepairProvider.file = file
        RepairProvider.sequentialOnly = false
        RepairProvider.beforeWriteOpen = null
        RepairProvider.modes.clear()
        Robolectric.buildContentProvider(RepairProvider::class.java).create("integrity-repair-files")
        return Uri.parse("content://integrity-repair-files/image")
    }

    class RepairProvider : ContentProvider() {
        companion object {
            lateinit var file: File
            var sequentialOnly = false
            var beforeWriteOpen: (() -> Unit)? = null
            val modes = mutableListOf<String>()
        }

        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            modes += mode
            if (sequentialOnly && mode == "rw") throw FileNotFoundException("random access unavailable")
            if (mode.contains('w')) beforeWriteOpen?.invoke()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri) = "image/png"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Test
    fun `content input is closed after both successful and failed copies`() {
        val context = RuntimeEnvironment.getApplication()
        for (fail in listOf(false, true)) {
            var closed = false
            val uri = Uri.parse("content://integrity/input-$fail")
            val input = object : ByteArrayInputStream(ByteArray(12)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (fail) throw IOException("read failed")
                    return super.read(buffer, offset, length)
                }
                override fun close() { closed = true }
            }
            shadowOf(context.contentResolver).registerInputStream(uri, input)

            assertFalse(ImageIntegrityValidator.repairUriIfPossible(context, uri))

            assertTrue("input must close even when copy fails=$fail", closed)
        }
    }

    @Test
    fun `concurrent content repairs use different temporary files`() {
        val context = RuntimeEnvironment.getApplication()
        val opened = CountDownLatch(2)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val existing = context.cacheDir.listFiles().orEmpty().toSet()
        try {
            val futures = (0..1).map { index ->
                val uri = Uri.parse("content://integrity/concurrent-$index")
                val input = object : ByteArrayInputStream(ByteArray(12)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        opened.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        return super.read(buffer, offset, length)
                    }
                }
                shadowOf(context.contentResolver).registerInputStream(uri, input)
                executor.submit<Boolean> { ImageIntegrityValidator.repairUriIfPossible(context, uri) }
            }
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            val temporaryFiles = context.cacheDir.listFiles().orEmpty().filter {
                it !in existing && it.name.startsWith("ecc_heal_")
            }
            assertEquals("each repair must own its temporary file", 2, temporaryFiles.size)
            release.countDown()
            futures.forEach { assertFalse(it.get(5, TimeUnit.SECONDS)) }
            assertTrue(temporaryFiles.none { it.exists() })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}

/** A one-pixel RGBA PNG and the same file with one IDAT data bit flipped. */
internal fun repairablePngBytes(): Pair<ByteArray, ByteArray> {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            output.writeInt(data.size)
            output.write(typeBytes)
            output.write(data)
            output.writeInt(CRC32().apply { update(typeBytes); update(data) }.value.toInt())
        }
        chunk("IHDR", ByteArray(13).apply {
            this[3] = 1
            this[7] = 1
            this[8] = 8
            this[9] = 6
        })
        val compressed = ByteArrayOutputStream().also { compressed ->
            DeflaterOutputStream(compressed).use { it.write(ByteArray(5)) }
        }.toByteArray()
        chunk("IDAT", compressed)
        chunk("IEND", ByteArray(0))
    }
    val healthy = bytes.toByteArray()
    val damaged = healthy.copyOf().apply { this[45] = (this[45].toInt() xor 0x80).toByte() }
    return healthy to damaged
}
