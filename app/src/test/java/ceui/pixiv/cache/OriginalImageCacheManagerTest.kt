package ceui.pixiv.cache

import ceui.pixiv.download.StageStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

class OriginalImageCacheManagerTest {

    private lateinit var cacheDir: File
    private lateinit var stagingDir: File

    @Before
    fun setUp() {
        cacheDir = File.createTempFile("orig_cache_", "_d").apply { delete(); mkdirs() }
        stagingDir = File.createTempFile("orig_staging_", "_d").apply { delete(); mkdirs() }
    }

    @After
    fun tearDown() {
        cacheDir.listFiles()?.forEach { it.delete() }
        cacheDir.delete()
        stagingDir.listFiles()?.forEach { it.delete() }
        stagingDir.delete()
    }

    @Test
    fun `keyForUrl produces stable sha256 prefix`() {
        val url = "https://i.pximg.net/img-original/img/2026/01/01/00/00/00/12345_p0.jpg"
        val key = OriginalImageCacheManager.keyForUrl(url)
        assertEquals(40, key.length)
        assertEquals(StageStore.keyForUrl(url), key)
    }

    @Test
    fun `LRU trim removes oldest files when exceeding max bytes`() {
        val f1 = File(cacheDir, "f1.jpg").apply {
            writeBytes(ByteArray(1024 * 1024 * 2)) // 2 MB
            setLastModified(1000L)
        }
        val f2 = File(cacheDir, "f2.jpg").apply {
            writeBytes(ByteArray(1024 * 1024 * 3)) // 3 MB
            setLastModified(2000L)
        }
        val f3 = File(cacheDir, "f3.jpg").apply {
            writeBytes(ByteArray(1024 * 1024 * 4)) // 4 MB
            setLastModified(3000L)
        }

        assertEquals(9L * 1024 * 1024, cacheDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() })

        OriginalImageCacheManager.trimDir(cacheDir, 5L * 1024 * 1024) // 5 MB quota

        // f1 was oldest (1000L) -> deleted first, reducing to 7MB.
        // f2 was next oldest (2000L) -> deleted next, reducing to 4MB <= 5MB.
        // f3 (3000L) remains.
        assertFalse(f1.exists())
        assertFalse(f2.exists())
        assertTrue(f3.exists())
    }

    @Test
    fun `find hits only the committed file name and misses otherwise`() {
        val url = "https://i.pximg.net/img-original/img/2026/01/01/00/00/00/12345_p0.png"
        assertNull(OriginalImageCacheManager.find(cacheDir, url))

        val key = OriginalImageCacheManager.keyForUrl(url)
        val part = StageStore.partFile(stagingDir, key).apply { writeBytes(ByteArray(16)) }
        val committed = OriginalImageCacheManager.commit(cacheDir, stagingDir, url, part)

        assertEquals("$key.png", committed.name)
        assertFalse(part.exists())
        assertEquals(committed, OriginalImageCacheManager.find(cacheDir, url))

        committed.writeBytes(ByteArray(0))
        assertNull(OriginalImageCacheManager.find(cacheDir, url))
    }
}
