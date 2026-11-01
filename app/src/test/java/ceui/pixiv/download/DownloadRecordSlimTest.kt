package ceui.pixiv.download

import ceui.loxia.ImageUrls
import ceui.loxia.Novel
import ceui.loxia.Series
import ceui.loxia.Tag
import ceui.loxia.User
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.MetaPage
import ceui.pixiv.api.model.MetaSinglePage
import ceui.pixiv.download.model.DownloadRecordSlim
import ceui.pixiv.ui.download.originalUrlsOf
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRecordSlimTest {

    @Test
    fun testMultiPageDirectoryLevelCompression() {
        val baseDirectory = "https://i.pximg.net/img-original/img/2026/10/28/12/34/56/"
        val pages = (0 until 28).map { i ->
            MetaPage(
                image_urls = ImageUrls(
                    square_medium = "https://i.pximg.net/c/360x360_70/img-master/img/2026/10/28/12/34/56/12345678_p${i}_square1200.jpg",
                    medium = "https://i.pximg.net/c/540x540_70/img-master/img/2026/10/28/12/34/56/12345678_p${i}_master1200.jpg",
                    large = "https://i.pximg.net/c/600x1200_90/img-master/img/2026/10/28/12/34/56/12345678_p${i}_master1200.jpg",
                    original = "${baseDirectory}12345678_p${i}.jpg"
                )
            )
        }
        val fullIllust = Illust(
            id = 12345678L,
            title = "Sample 28P Manga Title",
            caption = "<p>Very long caption description with HTML tags and commentary that takes up several hundred bytes in DB...</p>",
            type = "illust",
            page_count = 28,
            create_date = "2026-10-28T12:34:56+09:00",
            width = 1200,
            height = 1800,
            x_restrict = 0,
            illust_ai_type = 1,
            user = User(id = 88888L, name = "ArtistName", profile_image_urls = ImageUrls(medium = "https://i.pximg.net/user/avatar.jpg")),
            image_urls = ImageUrls(
                square_medium = "https://i.pximg.net/c/360x360_70/img-master/img/2026/10/28/12/34/56/12345678_p0_square1200.jpg",
                medium = "https://i.pximg.net/c/540x540_70/img-master/img/2026/10/28/12/34/56/12345678_p0_master1200.jpg"
            ),
            meta_pages = pages,
            tags = listOf(Tag(name = "Tag1"), Tag(name = "Tag2"), Tag(name = "Original")),
            tools = listOf("Photoshop", "CLIP STUDIO PAINT"),
            total_bookmarks = 5000,
            total_view = 30000,
        )

        val rawJson = Gson().toJson(fullIllust)
        val slimJson = DownloadRecordSlim.createIllustJson(fullIllust)

        // Raw JSON is ~14KB, Slim JSON with directory compression should be well below 2KB (>80% reduction)
        assertTrue(
            "Slim JSON should be significantly smaller than raw JSON. Raw=${rawJson.length}, Slim=${slimJson.length}",
            slimJson.length < rawJson.length * 0.25
        )

        val slimIllust = Gson().fromJson(slimJson, Illust::class.java)
        assertEquals(12345678L, slimIllust.id)
        assertEquals(28, slimIllust.page_count)
        assertNull(slimIllust.caption)
        assertNull(slimIllust.tools)

        // Verify meta_pages structure
        val slimPages = slimIllust.meta_pages
        assertNotNull(slimPages)
        assertEquals(28, slimPages!!.size)

        // p0 retains full URL
        assertEquals("${baseDirectory}12345678_p0.jpg", slimPages[0].image_urls?.original)

        // p1..p27 only retain filename relative to "/"
        assertEquals("12345678_p1.jpg", slimPages[1].image_urls?.original)
        assertEquals("12345678_p27.jpg", slimPages[27].image_urls?.original)

        // Fully resolve URLs and assert 100% fidelity to the original URLs
        val resolvedUrls = originalUrlsOf(slimIllust)
        assertEquals(28, resolvedUrls.size)
        for (i in 0 until 28) {
            assertEquals("${baseDirectory}12345678_p${i}.jpg", resolvedUrls[i])
        }
    }

    @Test
    fun testPessimisticCaseIrregularFilenamesUnderSameDirectory() {
        val baseDir = "https://i.pximg.net/img-original/img/2026/10/28/12/34/56/"
        val irregularPages = listOf(
            MetaPage(image_urls = ImageUrls(original = "${baseDir}cover_art.jpg")),
            MetaPage(image_urls = ImageUrls(original = "${baseDir}random_uuid_page_one.png")),
            MetaPage(image_urls = ImageUrls(original = "${baseDir}rough_sketch_final.webp")),
        )

        val illust = Illust(
            id = 55555L,
            title = "Irregular Artwork",
            page_count = 3,
            meta_pages = irregularPages
        )

        val slim = DownloadRecordSlim.toSlim(illust)
        val slimPages = slim.meta_pages
        assertNotNull(slimPages)
        assertEquals(3, slimPages!!.size)

        assertEquals("${baseDir}cover_art.jpg", slimPages[0].image_urls?.original)
        assertEquals("random_uuid_page_one.png", slimPages[1].image_urls?.original)
        assertEquals("rough_sketch_final.webp", slimPages[2].image_urls?.original)

        val resolved = originalUrlsOf(slim)
        assertEquals(3, resolved.size)
        assertEquals("${baseDir}cover_art.jpg", resolved[0])
        assertEquals("${baseDir}random_uuid_page_one.png", resolved[1])
        assertEquals("${baseDir}rough_sketch_final.webp", resolved[2])
    }

    @Test
    fun testLegacyAbsoluteUrlsResolution() {
        val legacyPages = listOf(
            MetaPage(image_urls = ImageUrls(original = "https://i.pximg.net/old_p0.jpg")),
            MetaPage(image_urls = ImageUrls(original = "https://i.pximg.net/old_p1.jpg")),
        )
        val illust = Illust(
            id = 1111L,
            page_count = 2,
            meta_pages = legacyPages
        )

        val resolved = originalUrlsOf(illust)
        assertEquals(2, resolved.size)
        assertEquals("https://i.pximg.net/old_p0.jpg", resolved[0])
        assertEquals("https://i.pximg.net/old_p1.jpg", resolved[1])
    }

    @Test
    fun testSinglePageOriginalUrls() {
        val singleIllust = Illust(
            id = 12345678L,
            title = "SinglePage",
            page_count = 1,
            meta_pages = null,
            meta_single_page = MetaSinglePage(
                original_image_url = "https://i.pximg.net/img-original/img/2026/10/28/12/34/56/12345678_p0.jpg"
            )
        )

        val urls = originalUrlsOf(singleIllust)
        assertEquals(1, urls.size)
        assertEquals("https://i.pximg.net/img-original/img/2026/10/28/12/34/56/12345678_p0.jpg", urls[0])
    }

    @Test
    fun testNovelSlimming() {
        val fullNovel = Novel(
            id = 9999L,
            title = "Novel Title",
            caption = "Very long novel synopsis...",
            user = User(id = 777L, name = "AuthorName"),
            image_urls = ImageUrls(square_medium = "https://i.pximg.net/cover.jpg"),
            page_count = 10,
            create_date = "2026-10-28",
            series = Series(id = 123L, title = "Novel Series")
        )

        val slimJson = DownloadRecordSlim.createNovelJson(fullNovel)
        val slimNovel = Gson().fromJson(slimJson, Novel::class.java)

        assertEquals(9999L, slimNovel.id)
        assertEquals("Novel Title", slimNovel.title)
        assertEquals("AuthorName", slimNovel.user?.name)
        assertNull(slimNovel.caption)
        assertEquals(123L, slimNovel.series?.id)
    }

    @Test
    fun testSlimIllustJsonPreservesStub() {
        val stub = "{\"id\":123,\"shaft_imported\":true}"
        val result = DownloadRecordSlim.slimIllustJson(stub)
        assertEquals(stub, result)
    }

    @Test
    fun testSlimDownloadJsonWithIllust() {
        val baseDir = "https://i.pximg.net/img-original/img/2026/10/28/12/34/56/"
        val pages = listOf(
            MetaPage(image_urls = ImageUrls(original = "${baseDir}100_p0.jpg")),
            MetaPage(image_urls = ImageUrls(original = "${baseDir}100_p1.jpg")),
            MetaPage(image_urls = ImageUrls(original = "${baseDir}100_p2.jpg"))
        )
        val illust = Illust(
            id = 100L,
            title = "Test Illust",
            caption = "A very long description that takes up useless space in download records",
            page_count = 3,
            meta_pages = pages
        )
        val rawJson = Gson().toJson(illust)
        val slimJson = DownloadRecordSlim.slimDownloadJson("100_p0.jpg", rawJson)
        assertNotNull(slimJson)
        assertTrue(slimJson!!.length < rawJson.length)
        assertFalse(slimJson.contains("A very long description"))

        val parsed = Gson().fromJson(slimJson, Illust::class.java)
        assertEquals(100L, parsed.id)
        val resolved = originalUrlsOf(parsed)
        assertEquals(3, resolved.size)
        assertEquals("${baseDir}100_p0.jpg", resolved[0])
        assertEquals("${baseDir}100_p1.jpg", resolved[1])
        assertEquals("${baseDir}100_p2.jpg", resolved[2])
    }

    @Test
    fun testSlimDownloadJsonWithNovel() {
        val novel = Novel(
            id = 888L,
            title = "Test Novel",
            caption = "This is a super long novel synopsis that shouldn't be duplicated in SQLite",
            user = User(id = 99L, name = "Author")
        )
        val rawJson = Gson().toJson(novel)
        val slimJson = DownloadRecordSlim.slimDownloadJson("pixiv_shaft_novel_888", rawJson)
        assertNotNull(slimJson)
        assertTrue(slimJson!!.length < rawJson.length)
        assertFalse(slimJson.contains("super long novel synopsis"))

        val parsed = Gson().fromJson(slimJson, Novel::class.java)
        assertEquals(888L, parsed.id)
        assertEquals("Test Novel", parsed.title)
        assertNull(parsed.caption)
    }
}
