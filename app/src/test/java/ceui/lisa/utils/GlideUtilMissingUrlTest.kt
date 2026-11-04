package ceui.lisa.utils

import ceui.loxia.ImageUrls
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.MetaPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlideUtilMissingUrlTest {

    @Test
    fun missingMediumInPresentImageUrlsDoesNotCrash() {
        assertNull(GlideUtil.getMediumImg(Illust(id = 1L, image_urls = ImageUrls())))
        assertNull(GlideUtil.getMediumImg(Illust(id = 2L, image_urls = ImageUrls(medium = ""))))
    }

    @Test
    fun missingPageUrlsFallBackToAvailableImage() {
        val illust = Illust(
            id = 3L,
            page_count = 2,
            image_urls = ImageUrls(large = "https://img.test/cover.jpg"),
            meta_pages = listOf(
                MetaPage(ImageUrls(large = "https://img.test/page-large.jpg")),
                MetaPage(),
            ),
        )

        assertEquals("https://img.test/page-large.jpg", GlideUtil.getOriginalImage(illust, 0)?.cacheKey)
        assertEquals("https://img.test/cover.jpg", GlideUtil.getLargeImage(illust, 1)?.cacheKey)
    }
}
