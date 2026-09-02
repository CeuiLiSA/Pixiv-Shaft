package ceui.pixiv.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProgressTest {

    @Test
    fun `percent is null when content length is unknown`() {
        assertNull(DownloadProgress(bytesRead = 500, contentLength = null, isDone = false).percent)
        assertNull(DownloadProgress(bytesRead = 500, contentLength = null, isDone = true).percent)
    }

    @Test
    fun `percent is null for a zero-length body`() {
        assertNull(DownloadProgress(bytesRead = 0, contentLength = 0, isDone = true).percent)
    }

    @Test
    fun `percent is zero before the first byte`() {
        assertEquals(0, DownloadProgress(bytesRead = 0, contentLength = 1_000, isDone = false).percent)
    }

    @Test
    fun `percent truncates instead of rounding`() {
        // 999 / 1000 = 99.9% → 99，不能四舍五入成 100：100 只能在真正读完时出现。
        assertEquals(99, DownloadProgress(bytesRead = 999, contentLength = 1_000, isDone = false).percent)
        assertEquals(1, DownloadProgress(bytesRead = 19, contentLength = 1_000, isDone = false).percent)
        assertEquals(0, DownloadProgress(bytesRead = 9, contentLength = 1_000, isDone = false).percent)
    }

    @Test
    fun `percent is exactly 100 when bytes read equals content length`() {
        assertEquals(100, DownloadProgress(bytesRead = 1_000, contentLength = 1_000, isDone = true).percent)
    }

    @Test
    fun `percent is clamped to 100 when the server under-reported the length`() {
        assertEquals(100, DownloadProgress(bytesRead = 1_500, contentLength = 1_000, isDone = true).percent)
    }

    @Test
    fun `percent does not overflow on multi-gigabyte bodies`() {
        val fourGiB = 4L * 1024 * 1024 * 1024
        assertEquals(50, DownloadProgress(bytesRead = fourGiB / 2, contentLength = fourGiB, isDone = false).percent)
        assertEquals(100, DownloadProgress(bytesRead = fourGiB, contentLength = fourGiB, isDone = true).percent)
    }
}
