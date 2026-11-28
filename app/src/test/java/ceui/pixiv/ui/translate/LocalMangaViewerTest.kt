package ceui.pixiv.ui.translate

import android.app.Activity
import android.app.Application
import android.content.ClipData
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
import ceui.pixiv.ui.detail.DownloadFab
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
        overwrite: OverwritePolicy = OverwritePolicy.Replace,
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
    fun `parsePickerResult handles single data, multi ClipData, and cancellation`() {
        val u1 = Uri.parse("content://media/picker/0/media/1")
        val u2 = Uri.parse("content://media/picker/0/media/2")

        assertTrue(LocalMangaViewer.parsePickerResult(Activity.RESULT_CANCELED, Intent().setData(u1)).isEmpty())
        assertEquals(
            listOf(u1),
            LocalMangaViewer.parsePickerResult(Activity.RESULT_OK, Intent().setData(u1)),
        )
        val clip = ClipData.newRawUri("p", u1).apply { addItem(ClipData.Item(u2)) }
        assertEquals(
            listOf(u1, u2),
            LocalMangaViewer.parsePickerResult(Activity.RESULT_OK, Intent().apply { clipData = clip }),
        )
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
    fun `buildTranslatedDisplayName preserves stem, disambiguates multi-page names, and appends _translated png`() {
        assertEquals("page_01_translated.png", LocalMangaViewer.buildTranslatedDisplayName("page_01.jpg", 0, 1))
        assertEquals("vol.1.ch2_translated.png", LocalMangaViewer.buildTranslatedDisplayName("vol.1.ch2.webp", 0, 1))
        assertEquals("already_translated.png", LocalMangaViewer.buildTranslatedDisplayName("already_translated.png", 0, 1))
        assertEquals("local_manga_translated.png", LocalMangaViewer.buildTranslatedDisplayName("_translated.png", 0, 1))

        // Multi-page same-named sources (e.g. ch1/001.jpg & ch2/001.jpg) get distinct _pN suffixes
        assertEquals("001_p1_translated.png", LocalMangaViewer.buildTranslatedDisplayName("001.jpg", 0, 2))
        assertEquals("001_p2_translated.png", LocalMangaViewer.buildTranslatedDisplayName("001.jpg", 1, 2))
        assertEquals(
            "003_p3_translated.png",
            LocalMangaViewer.buildTranslatedDisplayName("primary:Download/manga/003.png", 2, 5),
        )
        assertEquals("already_p2_translated.png", LocalMangaViewer.buildTranslatedDisplayName("already_translated.png", 1, 3))
        assertEquals("local_manga_p4_translated.png", LocalMangaViewer.buildTranslatedDisplayName(null, 3, 5))
        assertEquals("local_manga_p2_translated.png", LocalMangaViewer.buildTranslatedDisplayName("   ", 1, 2))
    }

    @Test
    fun `startSaveTranslatedPage writes distinct multi-page files under Replace and tracks state flow`() = runBlocking {
        val root = temp.newFolder("out")
        val backend = DirBackend(root)
        val cfg = configWith(overwrite = OverwritePolicy.Replace)
        val downloads = Downloads(cfg) { backend }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val toasts = mutableListOf<String>()

        val p0Png = temp.newFile("manga_translated_p0_1.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 1))
        }
        val p1Png = temp.newFile("manga_translated_p1_2.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 2, 2))
        }

        assertEquals(DownloadFab.Idle, LocalMangaViewer.saveFabState(p0Png))
        assertTrue(
            LocalMangaViewer.startSaveTranslatedPage(
                context = context,
                translatedFile = p0Png,
                sourceUriString = "file:///storage/emulated/0/ch1/001.jpg",
                pageIndex = 0,
                pageCount = 2,
                downloads = downloads,
                config = cfg,
                scope = scope,
                onToast = { toasts += it },
            ),
        )
        LocalMangaViewer.saveStates.first { it[p0Png.path] == LocalMangaViewer.SaveState.Done }

        assertTrue(
            LocalMangaViewer.startSaveTranslatedPage(
                context = context,
                translatedFile = p1Png,
                sourceUriString = "file:///storage/emulated/0/ch2/001.jpg",
                pageIndex = 1,
                pageCount = 2,
                downloads = downloads,
                config = cfg,
                scope = scope,
                onToast = { toasts += it },
            ),
        )
        LocalMangaViewer.saveStates.first { it[p1Png.path] == LocalMangaViewer.SaveState.Done }

        val out0 = File(root, "ShaftImages/001_p1_translated.png")
        val out1 = File(root, "ShaftImages/001_p2_translated.png")
        assertTrue(out0.isFile)
        assertTrue(out1.isFile)
        assertArrayEquals(p0Png.readBytes(), out0.readBytes())
        assertArrayEquals(p1Png.readBytes(), out1.readBytes())
        assertEquals("image/png", backend.writtenMimes["ShaftImages/001_p1_translated.png"])
        assertEquals("image/png", backend.writtenMimes["ShaftImages/001_p2_translated.png"])
        assertEquals(DownloadFab.Done, LocalMangaViewer.saveFabState(p0Png))
        assertEquals(DownloadFab.Done, LocalMangaViewer.saveFabState(p1Png))
        assertEquals(2, backend.finishCount)
        assertEquals(0, backend.abortCount)
        assertEquals(2, toasts.size)
    }

    @Test
    fun `startSaveTranslatedPage handles Skip policy and reverts state with abort on failure`() = runBlocking {
        val root = temp.newFolder("out_skip")
        val backend = DirBackend(root)
        val cfgSkip = configWith(overwrite = OverwritePolicy.Skip)
        val downloadsSkip = Downloads(cfgSkip) { backend }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val toasts = mutableListOf<String>()

        val srcPng = temp.newFile("manga_translated_p0_3.png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 9))
        }
        // First write creates target
        LocalMangaViewer.saveTranslatedPage(
            context = context,
            translatedFile = srcPng,
            sourceUriString = "file:///storage/emulated/0/ch01_005.jpg",
            pageIndex = 0,
            pageCount = 1,
            downloads = downloadsSkip,
            config = cfgSkip,
        )
        assertEquals(1, backend.finishCount)

        // Skip policy when target already exists marks Done without rewriting
        assertTrue(
            LocalMangaViewer.startSaveTranslatedPage(
                context = context,
                translatedFile = srcPng,
                sourceUriString = "file:///storage/emulated/0/ch01_005.jpg",
                pageIndex = 0,
                pageCount = 1,
                downloads = downloadsSkip,
                config = cfgSkip,
                scope = scope,
                onToast = { toasts += it },
            ),
        )
        LocalMangaViewer.saveStates.first { it[srcPng.path] == LocalMangaViewer.SaveState.Done }
        assertEquals(1, backend.finishCount)
        assertTrue(LocalMangaViewer.isTranslationSaved(srcPng))

        // Failure aborts handle, toasts error, and clears state back to Idle
        LocalMangaViewer.clearSavedStateForTest()
        backend.failOnFinish = true
        val cfgRename = configWith(overwrite = OverwritePolicy.Rename)
        val downloadsRename = Downloads(cfgRename) { backend }
        toasts.clear()
        assertTrue(
            LocalMangaViewer.startSaveTranslatedPage(
                context = context,
                translatedFile = srcPng,
                sourceUriString = "file:///storage/emulated/0/ch01_006.jpg",
                pageIndex = 0,
                pageCount = 1,
                downloads = downloadsRename,
                config = cfgRename,
                scope = scope,
                onToast = { toasts += it },
            ),
        )
        LocalMangaViewer.saveStates.first { srcPng.path !in it }
        assertEquals(1, backend.abortCount)
        assertEquals(DownloadFab.Idle, LocalMangaViewer.saveFabState(srcPng))
        assertEquals(1, toasts.size)
        assertTrue(toasts[0].contains("finish failed"))
    }
}
