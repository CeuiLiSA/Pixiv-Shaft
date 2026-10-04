package ceui.pixiv.ui.bulk

import ceui.pixiv.download.StageStore
import org.junit.Assert.assertEquals
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
}
