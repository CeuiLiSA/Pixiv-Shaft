package ceui.pixiv.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** 下载失败时识别「设备空间不足」，决定是否整体暂停（pixez#1361）。 */
class StorageSpaceGuardTest {

    @Test
    fun `recognizes ENOSPC from the write syscall`() {
        assertTrue(StorageSpaceGuard.isOutOfSpace(IOException("write failed: ENOSPC (No space left on device)")))
    }

    @Test
    fun `recognizes ENOSPC wrapped by another exception`() {
        val cause = IOException("No space left on device")
        assertTrue(StorageSpaceGuard.isOutOfSpace(RuntimeException("commit failed", cause)))
    }

    @Test
    fun `ordinary network failures are not out of space`() {
        assertFalse(StorageSpaceGuard.isOutOfSpace(IOException("HTTP 403")))
        assertFalse(StorageSpaceGuard.isOutOfSpace(IOException()))
        assertFalse(StorageSpaceGuard.isOutOfSpace(null))
    }
}
