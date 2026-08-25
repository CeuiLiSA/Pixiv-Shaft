package ceui.lisa.download

import ceui.lisa.utils.Params
import ceui.loxia.Illust
import ceui.loxia.ImageUrls
import ceui.loxia.MetaPage
import ceui.loxia.MetaSinglePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IllustDownloadUrlFallbackTest {

    @Test
    fun `single page missing meta original falls back to large`() {
        val illust = Illust(
            id = 1L,
            page_count = 1,
            meta_single_page = MetaSinglePage(original_image_url = null),
            image_urls = ImageUrls(large = "https://img.test/large.jpg"),
        )

        assertEquals(
            "https://img.test/large.jpg",
            IllustDownload.getUrl(illust, 0, Params.IMAGE_RESOLUTION_ORIGINAL),
        )
    }

    @Test
    fun `multi page missing original falls back within the requested page`() {
        val illust = Illust(
            id = 2L,
            page_count = 2,
            image_urls = ImageUrls(large = "https://img.test/cover.jpg"),
            meta_pages = listOf(
                MetaPage(ImageUrls(original = "https://img.test/p0-original.jpg")),
                MetaPage(ImageUrls(medium = "https://img.test/p1-medium.jpg")),
            ),
        )

        assertEquals(
            "https://img.test/p1-medium.jpg",
            IllustDownload.getUrl(illust, 1, Params.IMAGE_RESOLUTION_ORIGINAL),
        )
    }

    @Test
    fun `missing page image object falls back to cover URLs`() {
        val illust = Illust(
            id = 3L,
            page_count = 2,
            image_urls = ImageUrls(large = "https://img.test/cover.jpg"),
            meta_pages = listOf(MetaPage(), MetaPage()),
        )

        assertEquals(
            "https://img.test/cover.jpg",
            IllustDownload.getUrl(illust, 1, Params.IMAGE_RESOLUTION_ORIGINAL),
        )
    }

    @Test
    fun `large request falls back to medium`() {
        val illust = Illust(
            id = 4L,
            page_count = 1,
            image_urls = ImageUrls(medium = "https://img.test/medium.jpg"),
        )

        assertEquals(
            "https://img.test/medium.jpg",
            IllustDownload.getUrl(illust, 0, Params.IMAGE_RESOLUTION_LARGE),
        )
    }

    @Test
    fun `all missing URLs return null instead of throwing`() {
        val illust = Illust(
            id = 5L,
            page_count = 1,
            meta_single_page = MetaSinglePage(),
            image_urls = ImageUrls(),
        )

        assertNull(IllustDownload.getUrl(illust, 0, Params.IMAGE_RESOLUTION_ORIGINAL))
    }
}
