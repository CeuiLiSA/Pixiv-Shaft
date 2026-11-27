package ceui.pixiv.utils

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BitmapFileSaveTest {

    @Test
    fun saveAsJpgAndPngWriteNonEmptyFiles() {
        val bitmap = sampleBitmap()
        val jpg = File.createTempFile("shaft-jpg", ".jpg")
        val png = File.createTempFile("shaft-png", ".png")
        val webp = File.createTempFile("shaft-webp", ".webp")
        try {
            assertTrue(bitmap.saveAsJpgToFile(jpg, 90).length() > 0)
            assertTrue(bitmap.saveAsPngToFile(png, 100).length() > 0)
            assertTrue(bitmap.saveAsWebpToFile(webp, 100).length() > 0)
        } finally {
            jpg.delete()
            png.delete()
            webp.delete()
        }
    }

    @Test
    fun compressBitmapAndCloseClosesAfterSuccess() {
        val stream = TrackingOutputStream()
        stream.compressBitmapAndClose(sampleBitmap(), Bitmap.CompressFormat.PNG, 100)
        assertEquals(1, stream.closeCount)
        assertTrue(stream.flushed)
        assertTrue(stream.bytesWritten > 0)
    }

    @Test
    fun compressBitmapAndCloseClosesWhenFlushFails() {
        // Bitmap.compress may swallow write errors; flush is always invoked by the helper.
        val stream = TrackingOutputStream(failOnFlush = true)
        val error = assertThrows(IOException::class.java) {
            stream.compressBitmapAndClose(sampleBitmap(), Bitmap.CompressFormat.PNG, 100)
        }
        assertEquals("boom", error.message)
        assertEquals(1, stream.closeCount)
    }

    private fun sampleBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        return bitmap
    }
}

private class TrackingOutputStream(
    private val failOnFlush: Boolean = false,
) : OutputStream() {
    var closeCount = 0
        private set
    var flushed = false
        private set
    var bytesWritten = 0
        private set

    override fun write(b: Int) {
        bytesWritten++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        bytesWritten += len
    }

    override fun flush() {
        if (failOnFlush) throw IOException("boom")
        flushed = true
    }

    override fun close() {
        closeCount++
    }
}
