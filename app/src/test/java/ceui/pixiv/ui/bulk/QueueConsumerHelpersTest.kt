package ceui.pixiv.ui.bulk

import ceui.lisa.core.DownloadItem.DownloadState.DOWNLOADING
import ceui.lisa.core.DownloadItem.DownloadState.FAILED
import ceui.lisa.core.DownloadItem.DownloadState.INIT
import ceui.lisa.core.DownloadItem.DownloadState.PAUSED
import ceui.lisa.core.DownloadItem.DownloadState.SUCCESS
import ceui.pixiv.download.StageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [durableBytesOf] 的优先级：stage 文件（`.part`）的实际长度优先，取不到时退回 currentSize。
 *
 * 这条判据决定批量队列"这一轮有没有往前走"，进而决定重试圈数。之前用 currentSize
 * （主线程异步写的显示值、冷启动后必为 0）时，圈数会变成主线程负载的函数。
 */
class QueueConsumerHelpersTest {

    private fun tempDir(): File =
        File.createTempFile("stage_bytes_", "_dir").apply { delete(); mkdir() }

    private fun writeStage(dir: File, url: String, bytes: Int) {
        StageStore.partFile(dir, StageStore.keyForUrl(url)).writeBytes(ByteArray(bytes))
    }

    @Test fun `有 stage 文件时取它的实际长度，忽略 currentSize`() {
        val dir = tempDir()
        try {
            val url = "https://i.pximg.net/img-original/img/2026/01/01/00/00/00/1_p0.png"
            writeStage(dir, url, 1234)
            assertEquals(1234L, durableBytesOf(dir, url, 10L))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `stage 比 currentSize 小时取 currentSize`() {
        val dir = tempDir()
        try {
            val url = "https://i.pximg.net/a.png"
            writeStage(dir, url, 100)
            assertEquals(999L, durableBytesOf(dir, url, 999L))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `没有 stage 文件时退回 currentSize`() {
        val dir = tempDir()
        try {
            assertEquals(777L, durableBytesOf(dir, "https://i.pximg.net/missing.png", 777L))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `url 为空时退回 currentSize`() {
        val dir = tempDir()
        try {
            assertEquals(42L, durableBytesOf(dir, null, 42L))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `currentSize 为负按 0 处理`() {
        val dir = tempDir()
        try {
            assertEquals(0L, durableBytesOf(dir, "https://i.pximg.net/none.png", -5L))
        } finally {
            dir.deleteRecursively()
        }
    }

    // —— allPagesFailed：整条判失败的判据（跑生产函数，不是模型） ——

    @Test fun `全部 FAILED 才判整条失败`() {
        assertTrue(allPagesFailed(listOf(FAILED)))
        assertTrue(allPagesFailed(listOf(FAILED, FAILED, FAILED)))
    }

    @Test fun `SUCCESS 页还没被摘掉时不判失败`() {
        // 回归点：旧判据把 SUCCESS 当成"已 settle"，整条误判失败 → 重下已成功的页。
        assertFalse(allPagesFailed(listOf(SUCCESS)))
        assertFalse(allPagesFailed(listOf(FAILED, SUCCESS)))
    }

    @Test fun `还有未完成或暂停的页时不判失败`() {
        assertFalse(allPagesFailed(listOf(FAILED, INIT)))
        assertFalse(allPagesFailed(listOf(FAILED, DOWNLOADING)))
        assertFalse(allPagesFailed(listOf(FAILED, PAUSED)))
    }

    @Test fun `没有页不算失败`() {
        assertFalse(allPagesFailed(emptyList()))
    }

    // —— pagesToDispatch：拉入时按页号补页 ——

    @Test fun `首次拉入派发全部页`() {
        assertEquals(listOf(0, 1, 2), pagesToDispatch(3, emptySet(), emptySet()).toList())
    }

    @Test fun `重试只补缺页，content 残留页与本行已落盘页都不重派`() {
        // 回归点：旧实现 existing 非空就宣称"全部已派发"，10 页只带回 2 页时漏 8 页。
        assertEquals(
            listOf(1, 4, 5, 6, 7, 8, 9),
            pagesToDispatch(10, present = setOf(2, 3), doneByThisRow = setOf(0, 3)).toList(),
        )
    }

    @Test fun `全部页都已处理时为空`() {
        assertTrue(pagesToDispatch(2, setOf(0), setOf(1)).isEmpty())
    }
}
