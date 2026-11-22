package ceui.pixiv.download

import android.net.Uri
import ceui.pixiv.download.config.BucketConfig
import ceui.pixiv.download.config.BucketDefaults
import ceui.pixiv.download.config.DownloadConfig
import ceui.pixiv.download.config.DownloadItems
import ceui.pixiv.download.config.StorageChoice
import ceui.pixiv.download.model.Bucket
import ceui.pixiv.download.template.DefaultTemplates
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class IllustGalleryDestinationTest {

    private val mediaStoreImages = StorageChoice.MediaStore(StorageChoice.MediaStore.Collection.Images)
    private val safStorage = StorageChoice.Saf(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures%2FMyPixiv"))

    private fun config(illustTemplate: String, storage: StorageChoice) = DownloadConfig(
        defaults = BucketDefaults(template = illustTemplate, storage = storage),
        perBucket = mapOf(Bucket.Illust to BucketConfig(template = illustTemplate, storage = storage)),
    )

    @Test
    fun `saf storage with flat template writes directly to root without ShaftImages subfolder`() {
        val path = DownloadItems.illustGalleryDestination(
            "upscaled_123456789.png",
            config("{title}_{id}.{ext}", safStorage),
        )
        assertEquals(listOf("upscaled_123456789.png"), path.segments)
        assertEquals("", path.directory.joinToString("/"))
        assertEquals("upscaled_123456789.png", path.filename)
    }

    @Test
    fun `saf storage with subfolder template honours configured static subfolder`() {
        val path = DownloadItems.illustGalleryDestination(
            "rembg_9999.png",
            config("Shaft/{title} {id}.{ext}", safStorage),
        )
        assertEquals(listOf("Shaft", "rembg_9999.png"), path.segments)
        assertEquals("Shaft", path.directory.single())
    }

    @Test
    fun `saf storage with modern template stops before dynamic variables`() {
        val modernTemplate = "Shaft/Illusts/[?R18:R18/][?AI:AI/]{author} ({author_id})/{title} {id}.{ext}"
        val path = DownloadItems.illustGalleryDestination(
            "original_111.png",
            config(modernTemplate, safStorage),
        )
        assertEquals(listOf("Shaft", "Illusts", "original_111.png"), path.segments)
        assertEquals(listOf("Shaft", "Illusts"), path.directory)
    }

    @Test
    fun `mediastore storage uses template directory when present`() {
        val path = DownloadItems.illustGalleryDestination(
            "upscaled_abc.png",
            config(DefaultTemplates.ILLUST, mediaStoreImages),
        )
        assertEquals(listOf("ShaftImages", "upscaled_abc.png"), path.segments)
    }

    @Test
    fun `mediastore storage falls back to ShaftImages when template has no subfolder`() {
        val path = DownloadItems.illustGalleryDestination(
            "upscaled_abc.png",
            config("{title}_{id}.{ext}", mediaStoreImages),
        )
        assertEquals(listOf("ShaftImages", "upscaled_abc.png"), path.segments)
    }
}
