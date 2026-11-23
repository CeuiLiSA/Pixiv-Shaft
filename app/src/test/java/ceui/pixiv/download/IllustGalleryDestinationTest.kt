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

    @Test
    fun `literal square brackets in static directory names are preserved`() {
        val safPath = DownloadItems.illustGalleryDestination(
            "upscaled_1.png",
            config("[Pixiv]/[Favorites]/{title}_{id}.{ext}", safStorage),
        )
        assertEquals(listOf("[Pixiv]", "[Favorites]", "upscaled_1.png"), safPath.segments)

        val mediaStorePath = DownloadItems.illustGalleryDestination(
            "upscaled_2.png",
            config("[Pixiv]/{title}_{id}.{ext}", mediaStoreImages),
        )
        assertEquals(listOf("[Pixiv]", "upscaled_2.png"), mediaStorePath.segments)
    }

    @Test
    fun `escaped characters and backslash separators match TemplateParser semantics`() {
        val escapedBraces = DownloadItems.illustGalleryDestination(
            "upscaled_1.png",
            config("""\{Pixiv\}/My\ Folder/{title}_{id}.{ext}""", safStorage),
        )
        assertEquals(listOf("{Pixiv}", "My Folder", "upscaled_1.png"), escapedBraces.segments)

        val backslashSep = DownloadItems.illustGalleryDestination(
            "upscaled_2.png",
            config("""Shaft\\{title}_{id}.{ext}""", safStorage),
        )
        assertEquals(listOf("Shaft", "upscaled_2.png"), backslashSep.segments)
    }

    @Test
    fun `static filename or dynamic directory prefixes are not mistaken for directory segments`() {
        val filePrefix = DownloadItems.illustGalleryDestination(
            "upscaled_1.png",
            config("Shaft/prefix_{title}_{id}.{ext}", safStorage),
        )
        assertEquals(listOf("Shaft", "upscaled_1.png"), filePrefix.segments)

        val dynamicDirPrefix = DownloadItems.illustGalleryDestination(
            "upscaled_2.png",
            config("prefix_{author}/{title}_{id}.{ext}", safStorage),
        )
        assertEquals(listOf("upscaled_2.png"), dynamicDirPrefix.segments)
    }

    @Test
    fun `malformed persisted templates fall back to DefaultTemplates ILLUST like SafeTemplateRender`() {
        val unknownVar = DownloadItems.illustGalleryDestination(
            "upscaled_1.png",
            config("Broken/{unknown_var}.{ext}", safStorage),
        )
        assertEquals(listOf("ShaftImages", "upscaled_1.png"), unknownVar.segments)

        val badCondition = DownloadItems.illustGalleryDestination(
            "upscaled_2.png",
            config("Broken/[?p<100:x]/{id}.{ext}", safStorage),
        )
        assertEquals(listOf("ShaftImages", "upscaled_2.png"), badCondition.segments)

        val unclosedBrace = DownloadItems.illustGalleryDestination(
            "upscaled_3.png",
            config("Broken/{unclosed", safStorage),
        )
        assertEquals(listOf("ShaftImages", "upscaled_3.png"), unclosedBrace.segments)
    }

    @Test
    fun `whitespace-only directory segment matches RelativePath and FsSanitizer`() {
        val path = DownloadItems.illustGalleryDestination(
            "upscaled_1.png",
            config("Shaft/ /{title}_{id}.{ext}", safStorage),
        )
        assertEquals(listOf("Shaft", "_", "upscaled_1.png"), path.segments)
    }

    @Test
    fun `displayName path separators are sanitized into a single filename segment`() {
        val path = DownloadItems.illustGalleryDestination(
            "../evil/name.png",
            config("Shaft/{title}_{id}.{ext}", safStorage),
        )
        assertEquals(listOf("Shaft", "_evil_name.png"), path.segments)
    }
}
