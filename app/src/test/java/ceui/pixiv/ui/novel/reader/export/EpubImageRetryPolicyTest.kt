package ceui.pixiv.ui.novel.reader.export

import org.junit.Assert.*
import org.junit.Test

class EpubImageRetryPolicyTest {

    @Test
    fun `recent novel export tracker marks and consumes failure flag`() {
        RecentNovelExportTracker.consumeFailed() // ensure clean state
        assertFalse(RecentNovelExportTracker.consumeFailed())

        RecentNovelExportTracker.markFailed()
        assertTrue(RecentNovelExportTracker.consumeFailed())
        assertFalse(RecentNovelExportTracker.consumeFailed())
    }

    @Test
    fun `failed image info formats clipboard text with url and error`() {
        val error = RuntimeException("Connection timed out")
        val info = FailedImageInfo(
            key = "pixiv_12345_0",
            url = "https://i.pximg.net/c/600x1200_90_webp/img/test.jpg",
            error = error,
            novelId = "999",
            chapterTitle = "Chapter 1",
        )
        val text = info.formatForClipboard()
        assertTrue(text.contains("pixiv_12345_0"))
        assertTrue(text.contains("https://i.pximg.net/c/600x1200_90_webp/img/test.jpg"))
        assertTrue(text.contains("999"))
        assertTrue(text.contains("Chapter 1"))
        assertTrue(text.contains("Connection timed out"))
        assertFalse(text.contains("堆栈:"))
    }
}
