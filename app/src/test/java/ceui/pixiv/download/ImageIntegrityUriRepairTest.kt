package ceui.pixiv.download

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ImageIntegrityUriRepairTest {
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
