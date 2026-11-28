package ceui.pixiv.ui.translate

import android.app.Application
import android.content.Intent
import android.net.Uri
import ceui.lisa.download.IllustDownload
import ceui.lisa.utils.Params
import ceui.pixiv.api.model.Illust
import ceui.pixiv.download.Downloads
import ceui.pixiv.download.backend.StorageBackend
import ceui.pixiv.download.config.BucketDefaults
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.OverwritePolicy
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.RelativePath
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalMangaViewerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()
    private val storageKey = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)

    @After
    fun tearDown() {
        LocalMangaViewer.clearSavedStateForTest()
    }

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

    @Test
    fun `buildIllust constructs negative-id local manga for single and multi pages`() {
        val u1 = "content://media/picker/0/com.android.providers.media.photopicker/media/101"
        val u2 = "content://media/picker/0/com.android.providers.media.photopicker/media/102"

        val single = LocalMangaViewer.buildIllust(listOf(u1), "本地漫画翻译")
        assertTrue(single.id < 0L)
        assertTrue(LocalMangaViewer.isLocalManga(single))
        assertFalse(single.isGif())
        assertEquals(1, single.page_count)
        assertEquals(u1, IllustDownload.getUrl(single, 0, Params.IMAGE_RESOLUTION_ORIGINAL))

        val multi = LocalMangaViewer.buildIllust(listOf(u1, u2), "本地漫画翻译")
        assertTrue(multi.id < 0L)
        assertNotEquals(single.id, multi.id)
        assertEquals(LocalMangaViewer.sessionIdFor(listOf(u1, u2)), multi.id)
        assertNotEquals(
            LocalMangaViewer.sessionIdFor(listOf(u2, u1)),
            multi.id,
        )
        assertEquals(2, multi.page_count)
        assertEquals(u1, IllustDownload.getUrl(multi, 0, Params.IMAGE_RESOLUTION_ORIGINAL))
        assertEquals(u2, IllustDownload.getUrl(multi, 1, Params.IMAGE_RESOLUTION_ORIGINAL))

        assertFalse(LocalMangaViewer.isLocalManga(Illust(id = 12345L, type = "manga")))
    }

    @Test
    fun `createIntent propagates content uri read grants via ClipData and clamps page`() {
        val u1 = "content://media/picker/0/com.android.providers.media.photopicker/media/201"
        val u2 = "content://media/picker/0/com.android.providers.media.photopicker/media/202"
        val illust = LocalMangaViewer.buildIllust(listOf(u1, u2), "本地漫画翻译")

        val intent = LocalMangaViewer.createIntent(context, illust, initialPage = 99)
        assertEquals(LocalMangaViewer.DATA_TYPE, intent.getStringExtra("dataType"))
        assertEquals(1, intent.getIntExtra("index", -1))
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        val clip = intent.clipData
        assertNotNull(clip)
        assertEquals(2, clip!!.itemCount)
        assertEquals(Uri.parse(u1), clip.getItemAt(0).uri)
        assertEquals(Uri.parse(u2), clip.getItemAt(1).uri)
    }

    @Test
    fun `buildTranslatedDisplayName preserves stem and appends _translated png`() {
        assertEquals("page_01_translated.png", LocalMangaViewer.buildTranslatedDisplayName("page_01.jpg", 0))
        assertEquals("vol.1.ch2_translated.png", LocalMangaViewer.buildTranslatedDisplayName("vol.1.ch2.webp", 0))
        assertEquals(
            "003_translated.png",
            LocalMangaViewer.buildTranslatedDisplayName("primary:Download/manga/003.png", 2),
        )
        assertEquals("already_translated.png", LocalMangaViewer.buildTranslatedDisplayName("already_translated.png", 0))
        assertEquals("local_manga_p4_translated.png", LocalMangaViewer.buildTranslatedDisplayName(null, 3))
        assertEquals("local_manga_p2_translated.png", LocalMangaViewer.buildTranslatedDisplayName("   ", 1))
    }

    @Test
    fun `saveTranslatedPage writes PNG to Illust bucket and handles skip and abort`() = runBlocking {
        val root = temp.newFolder("out")
        val backend = DirBackend(root)
        val cfg = configWith(overwrite = OverwritePolicy.Skip)
        val downloads = Downloads(cfg) { backend }

        val srcPng = temp.newFile("manga_translated_p0_1.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4))
        }
        assertFalse(LocalMangaViewer.isTranslationSaved(srcPng))

        LocalMangaViewer.saveTranslatedPage(
            context = context,
            translatedFile = srcPng,
            sourceUriString = "file:///storage/emulated/0/Download/ch01_005.jpg",
            pageIndex = 0,
            downloads = downloads,
            config = cfg,
        )

        val expected = File(root, "ShaftImages/ch01_005_translated.png")
        assertTrue(expected.isFile)
        assertArrayEquals(srcPng.readBytes(), expected.readBytes())
        assertEquals("image/png", backend.writtenMimes["ShaftImages/ch01_005_translated.png"])
        assertEquals(1, backend.finishCount)
        assertEquals(0, backend.abortCount)
        assertTrue(LocalMangaViewer.isTranslationSaved(srcPng))

        // Skip policy when target already exists marks saved without re-writing
        LocalMangaViewer.clearSavedStateForTest()
        LocalMangaViewer.saveTranslatedPage(
            context = context,
            translatedFile = srcPng,
            sourceUriString = "file:///storage/emulated/0/Download/ch01_005.jpg",
            pageIndex = 0,
            downloads = downloads,
            config = cfg,
        )
        assertEquals(1, backend.finishCount)
        assertTrue(LocalMangaViewer.isTranslationSaved(srcPng))

        // Failure aborts handle and does not mark saved
        LocalMangaViewer.clearSavedStateForTest()
        backend.failOnFinish = true
        val cfgReplace = configWith(overwrite = OverwritePolicy.Rename)
        val downloadsReplace = Downloads(cfgReplace) { backend }
        var threw = false
        try {
            LocalMangaViewer.saveTranslatedPage(
                context = context,
                translatedFile = srcPng,
                sourceUriString = "file:///storage/emulated/0/Download/ch01_006.jpg",
                pageIndex = 1,
                downloads = downloadsReplace,
                config = cfgReplace,
            )
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        assertEquals(1, backend.abortCount)
        assertFalse(LocalMangaViewer.isTranslationSaved(srcPng))
    }
}
