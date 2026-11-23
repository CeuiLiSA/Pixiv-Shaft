package ceui.pixiv.snapshot

import android.content.Context
import android.net.Uri
import ceui.lisa.activities.Shaft
import ceui.loxia.ImageUrls
import ceui.loxia.User
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.MetaPage
import ceui.pixiv.api.model.MetaSinglePage
import ceui.pixiv.download.Downloads
import ceui.pixiv.download.backend.StorageBackend
import ceui.pixiv.download.config.BucketDefaults
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.OverwritePolicy
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.RelativePath
import ceui.pixiv.ui.detail.DownloadFab
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SnapshotImageSaverTest.TestApplication::class)
class SnapshotImageSaverTest {

    class TestApplication : Shaft() {
        override fun onCreate() = Unit
    }

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val storageKey = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)

    private fun configWith(
        template: String = "ShaftImages/{title}_{id}_p{page}.{ext}",
        overwrite: OverwritePolicy = OverwritePolicy.Rename,
    ) = DownloadConfig(
        defaults = BucketDefaults(template = template, storage = storageKey, overwrite = overwrite),
    )

    private class DirBackend(private val root: File) : StorageBackend {
        var finishCount = 0
        var abortCount = 0
        val writtenMimes = mutableMapOf<String, String>()
        var failOnFinish = false

        private fun targetFile(relPath: RelativePath): File = File(root, relPath.joinTo())

        override fun open(relPath: RelativePath, mime: String): StorageBackend.WriteHandle {
            val file = targetFile(relPath)
            file.parentFile?.mkdirs()
            writtenMimes[relPath.joinTo()] = mime
            val fos = FileOutputStream(file)
            return StorageBackend.WriteHandle(
                uri = Uri.fromFile(file),
                stream = fos,
                onFinish = {
                    if (failOnFinish) throw IllegalStateException("finish failed")
                    finishCount++
                },
                onAbort = {
                    abortCount++
                    file.delete()
                },
            )
        }

        override fun exists(relPath: RelativePath): Boolean = targetFile(relPath).isFile

        override fun delete(relPath: RelativePath): Boolean = targetFile(relPath).delete()
    }

    private fun samplePngBytes(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03)

    private fun sampleJpegBytes(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

    private fun sampleGifBytes(): ByteArray = "GIF89a123456".toByteArray()

    private fun sampleWebpBytes(): ByteArray = "RIFF1234WEBP".toByteArray()

    private fun sampleBmpBytes(): ByteArray = "BM123456".toByteArray()

    // ---------- resolveSnapshotImageExtension ----------

    @Test
    fun `sniffs image format from file magic bytes even when snapshot file ends with img`() {
        val dir = temp.newFolder("magic")
        val png = File(dir, "p0.img").apply { writeBytes(samplePngBytes()) }
        val jpg = File(dir, "p1.img").apply { writeBytes(sampleJpegBytes()) }
        val gif = File(dir, "p2.img").apply { writeBytes(sampleGifBytes()) }
        val webp = File(dir, "p3.img").apply { writeBytes(sampleWebpBytes()) }
        val bmp = File(dir, "p4.img").apply { writeBytes(sampleBmpBytes()) }

        assertEquals("png", resolveSnapshotImageExtension(png))
        assertEquals("jpg", resolveSnapshotImageExtension(jpg))
        assertEquals("gif", resolveSnapshotImageExtension(gif))
        assertEquals("webp", resolveSnapshotImageExtension(webp))
        assertEquals("bmp", resolveSnapshotImageExtension(bmp))
    }

    @Test
    fun `falls back to known extension or jpg when magic bytes are unrecognized`() {
        val dir = temp.newFolder("fallback")
        val heic = File(dir, "p0.heic").apply { writeBytes(byteArrayOf(0x00, 0x01, 0x02)) }
        val unknown = File(dir, "p1.img").apply { writeBytes(byteArrayOf(0x00, 0x01, 0x02)) }

        assertEquals("heic", resolveSnapshotImageExtension(heic))
        assertEquals("jpg", resolveSnapshotImageExtension(unknown))
    }

    // ---------- buildSnapshotPageDownloadItem ----------

    @Test
    fun `uses archived snapshot file format instead of remote original url extension`() {
        val dir = temp.newFolder("ext-mismatch")
        // 非原图快照：远端 original 是 .png，但快照实际存档了 large 的 JPEG 字节
        val archivedJpg = File(dir, "p0.jpg").apply { writeBytes(sampleJpegBytes()) }
        val illust = Illust(
            id = 12345L,
            title = "Deleted Work",
            type = "illust",
            page_count = 1,
            width = 1000,
            height = 1200,
            create_date = "2025-01-02T03:04:05+09:00",
            user = User(id = 77L, name = "Artist"),
            meta_single_page = MetaSinglePage(original_image_url = "https://i.pximg.net/img-original/12345_p0.png"),
        )

        val item = buildSnapshotPageDownloadItem(illust, pageIndex = 0, file = archivedJpg)

        assertEquals("jpg", item.ext)
        assertEquals("image/jpeg", item.mime)
        assertEquals(12345L, item.meta.id)
        assertEquals("Deleted Work", item.meta.title)
        assertEquals("Artist", item.meta.author.name)
    }

    // ---------- saveSnapshotStaticPage / saveAllSnapshotStaticPages ----------

    @Test
    fun `saves single and multi-page snapshot files verbatim into configured download backend`() {
        val snapDir = temp.newFolder("snap-multi")
        val imagesDir = File(snapDir, "images").apply { mkdirs() }
        val p0Bytes = samplePngBytes()
        val p1Bytes = sampleJpegBytes()
        File(imagesDir, "p0.png").writeBytes(p0Bytes)
        File(imagesDir, "p1.img").writeBytes(p1Bytes)

        val illust = Illust(
            id = 999L,
            title = "Archive",
            type = "illust",
            page_count = 2,
            create_date = "2025-05-01T00:00:00+09:00",
            user = User(id = 1L, name = "Author"),
            meta_pages = listOf(
                MetaPage(ImageUrls(original = "https://i.pximg.net/999_p0.png")),
                MetaPage(ImageUrls(original = "https://i.pximg.net/999_p1.jpg")),
            ),
        )
        val data = SnapshotViewerData(
            snapshotDir = snapDir,
            manifest = SnapshotManifest(snapshotId = "snap-999", illustId = 999L, pageCount = 2),
            illust = illust,
            assets = mapOf(
                "https://i.pximg.net/999_p0.png" to "images/p0.png",
                "https://i.pximg.net/999_p1.jpg" to "images/p1.img",
            ),
            comments = null,
        )

        val outRoot = temp.newFolder("out")
        val backend = DirBackend(outRoot)
        val downloads = Downloads(configWith(), { backend })
        val progress = mutableListOf<Int>()

        val outcome = saveAllSnapshotStaticPages(
            context = context,
            downloads = downloads,
            data = data,
            writeExif = false,
            onProgress = { progress += it },
        )

        assertTrue(outcome.allSucceeded)
        assertEquals(2, outcome.total)
        assertEquals(2, outcome.savedCount)
        assertEquals(0, outcome.skippedCount)
        assertEquals(0, outcome.failedCount)
        assertEquals(listOf(50, 100), progress)
        assertEquals(2, backend.finishCount)
        assertEquals(0, backend.abortCount)

        val savedP0 = File(outRoot, "ShaftImages/Archive_999_p1.png")
        val savedP1 = File(outRoot, "ShaftImages/Archive_999_p2.jpg")
        assertTrue(savedP0.isFile)
        assertTrue(savedP1.isFile)
        assertArrayEquals(p0Bytes, savedP0.readBytes())
        assertArrayEquals(p1Bytes, savedP1.readBytes())
        assertEquals("image/png", backend.writtenMimes["ShaftImages/Archive_999_p1.png"])
        assertEquals("image/jpeg", backend.writtenMimes["ShaftImages/Archive_999_p2.jpg"])
    }

    @Test
    fun `respects Skip overwrite policy without failing`() {
        val snapDir = temp.newFolder("snap-skip")
        val imagesDir = File(snapDir, "images").apply { mkdirs() }
        File(imagesDir, "p0.png").writeBytes(samplePngBytes())

        val illust = Illust(
            id = 555L,
            title = "Solo",
            type = "illust",
            page_count = 1,
            create_date = "2025-05-01T00:00:00+09:00",
            meta_single_page = MetaSinglePage(original_image_url = "https://i.pximg.net/555_p0.png"),
        )
        val data = SnapshotViewerData(
            snapshotDir = snapDir,
            manifest = SnapshotManifest(snapshotId = "snap-555", illustId = 555L, pageCount = 1),
            illust = illust,
            assets = mapOf("https://i.pximg.net/555_p0.png" to "images/p0.png"),
            comments = null,
        )

        val outRoot = temp.newFolder("out-skip")
        val backend = DirBackend(outRoot)
        val downloads = Downloads(configWith(overwrite = OverwritePolicy.Skip), { backend })

        val first = saveSnapshotStaticPage(context, downloads, data, pageIndex = 0, writeExif = false)
        assertTrue(first.written)
        assertEquals(1, backend.finishCount)

        val second = saveSnapshotStaticPage(context, downloads, data, pageIndex = 0, writeExif = false)
        assertFalse(second.written)
        assertNull(second.uri)
        assertEquals(1, backend.finishCount)
    }

    @Test
    fun `aborts handle and cleans up target when commit fails`() {
        val snapDir = temp.newFolder("snap-abort")
        val src = File(snapDir, "p0.png").apply { writeBytes(samplePngBytes()) }
        val illust = Illust(
            id = 777L,
            title = "Abort",
            type = "illust",
            page_count = 1,
            create_date = "2025-05-01T00:00:00+09:00",
        )
        val item = buildSnapshotPageDownloadItem(illust, pageIndex = 0, file = src)
        val outRoot = temp.newFolder("out-abort")
        val backend = DirBackend(outRoot).apply { failOnFinish = true }
        val downloads = Downloads(configWith(), { backend })

        var threw = false
        try {
            writeFileToDownloads(downloads, item, src)
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue(threw)
        assertEquals(1, backend.abortCount)
        assertFalse(File(outRoot, "ShaftImages/Abort_777_p1.png").exists())
    }

    @Test
    fun `batch save reports partial failure while still saving valid pages`() {
        val snapDir = temp.newFolder("snap-partial")
        val imagesDir = File(snapDir, "images").apply { mkdirs() }
        File(imagesDir, "p0.png").writeBytes(samplePngBytes())
        // p1 文件刻意缺失

        val illust = Illust(
            id = 888L,
            title = "Partial",
            type = "illust",
            page_count = 2,
            create_date = "2025-05-01T00:00:00+09:00",
            meta_pages = listOf(
                MetaPage(ImageUrls(original = "https://i.pximg.net/888_p0.png")),
                MetaPage(ImageUrls(original = "https://i.pximg.net/888_p1.png")),
            ),
        )
        val data = SnapshotViewerData(
            snapshotDir = snapDir,
            manifest = SnapshotManifest(snapshotId = "snap-888", illustId = 888L, pageCount = 2),
            illust = illust,
            assets = mapOf(
                "https://i.pximg.net/888_p0.png" to "images/p0.png",
                "https://i.pximg.net/888_p1.png" to "images/p1.png",
            ),
            comments = null,
        )

        val outRoot = temp.newFolder("out-partial")
        val backend = DirBackend(outRoot)
        val downloads = Downloads(configWith(), { backend })

        val outcome = saveAllSnapshotStaticPages(context, downloads, data, writeExif = false)

        assertFalse(outcome.allSucceeded)
        assertEquals(2, outcome.total)
        assertEquals(1, outcome.savedCount)
        assertEquals(1, outcome.failedCount)
        assertTrue(File(outRoot, "ShaftImages/Partial_888_p1.png").isFile)
    }

    // ---------- FAB state transitions ----------

    @Test
    fun `failed page retry clears stale All Done so button does not stay green`() {
        val snapId = "snap-state"
        val initial = mapOf(
            SnapshotImageSaver.SaveKey.All(snapId) to SnapshotImageSaver.SaveState.Done,
            SnapshotImageSaver.SaveKey.Page(snapId, 0) to SnapshotImageSaver.SaveState.Saving(0),
        )

        val afterFailure = reducePageSaveFinished(
            states = initial,
            snapshotId = snapId,
            page = 0,
            succeeded = false,
            isSinglePage = true,
        )

        assertEquals(DownloadFab.Idle, computePageFabState(afterFailure, snapId, page = 0))
        assertNull(afterFailure[SnapshotImageSaver.SaveKey.All(snapId)])
    }

    @Test
    fun `single-page save success marks both Page and All as Done`() {
        val snapId = "snap-single"
        val initial = mapOf<SnapshotImageSaver.SaveKey, SnapshotImageSaver.SaveState>(
            SnapshotImageSaver.SaveKey.Page(snapId, 0) to SnapshotImageSaver.SaveState.Saving(0),
        )

        val afterSuccess = reducePageSaveFinished(
            states = initial,
            snapshotId = snapId,
            page = 0,
            succeeded = true,
            isSinglePage = true,
        )

        assertEquals(DownloadFab.Done, computePageFabState(afterSuccess, snapId, page = 0))
        assertEquals(
            SnapshotImageSaver.SaveState.Done,
            afterSuccess[SnapshotImageSaver.SaveKey.All(snapId)],
        )
    }
}
