package ceui.pixiv.plaza

import ceui.loxia.ImageUrls
import ceui.loxia.Tag
import ceui.loxia.User
import ceui.pixiv.api.model.Illust
import ceui.pixiv.api.model.MetaPage
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PlazaLinkedWorkTest {
    private fun urls(page: Int) =
        ImageUrls(
            square_medium = "https://i.pximg.net/c/360x360_70/p${page}_square1200.jpg",
            medium = "https://i.pximg.net/c/540x540_70/p${page}_master1200.jpg",
            large = "https://i.pximg.net/c/600x1200_90/p${page}_master1200.jpg",
            original = "https://i.pximg.net/img-original/p$page.png",
        )

    private fun work(pages: Int) =
        Illust(
            id = 123,
            title = "Linked",
            caption = "<p>long html</p>",
            width = 1200,
            height = 1600,
            page_count = pages,
            image_urls = urls(0).copy(original = null),
            meta_pages = if (pages > 1) (0 until pages).map { MetaPage(urls(it)) } else null,
            is_bookmarked = true,
            tags = (1..50).map { Tag(name = "tag$it", translated_name = "标签$it", added_by_uploaded_user = true) } +
                Tag(name = null, translated_name = "nameless"),
            user = User(id = 7, name = "Artist", account = "artist", is_followed = true,
                profile_image_urls = ImageUrls(medium = "https://s.pximg.net/user/7.png", large = "x")),
            tools = listOf("SAI"),
        )

    private fun post(images: List<PlazaImage>, extensions: PlazaObjectExtensions?) =
        PlazaPost(1, 42, "Author", "", 1, 123, "illust", null, 0, 0, false, images,
            objectExtensions = extensions)

    @Test
    fun `pages come from meta_pages when present and from image_urls otherwise, capped at nine`() {
        assertEquals(listOf(0), work(1).linkedPages().map { it.index })
        assertEquals(urls(0).large, work(1).linkedPages().single().large)
        val many = work(12).linkedPages()
        assertEquals((0 until 9).toList(), many.map { it.index })
        assertEquals(urls(8).medium, many.last().medium)
        assertTrue(work(1).copy(image_urls = ImageUrls(large = "only-large")).linkedPages().isEmpty())
    }

    @Test
    fun `the linked work fills in only when the author uploaded nothing`() {
        val extensions = work(2).plazaExtensions()
        assertEquals(123L, post(emptyList(), extensions).linkedWork()?.id)
        val upload = PlazaImage("m", 10, 10, "image/jpeg", "https://media.pixshaft.com/m", 1)
        assertNull(post(listOf(upload), extensions).linkedWork())
        assertNull(post(emptyList(), null).linkedWork())
        assertNull(post(emptyList(), PlazaObjectExtensions()).linkedWork())
    }

    @Test
    fun `the composer attaches the work as the app-api returned it, or nothing without a medium URL`() {
        val original = work(12)
        assertSame(original, checkNotNull(original.plazaExtensions()).illust)
        // The wire shape is the app-api Illust shape, so the server's whitelist keys match; client
        // decorations stay out of it.
        val json = Gson().toJsonTree(original.plazaExtensions()).asJsonObject.getAsJsonObject("illust")
        assertEquals(123L, json["id"].asLong)
        assertEquals(urls(0).medium, json.getAsJsonObject("image_urls")["medium"].asString)
        assertEquals(12, json.getAsJsonArray("meta_pages").size())
        assertFalse(json.has("isRelated"))
        assertFalse(json.has("trendingScore"))
        assertNull(work(1).copy(image_urls = ImageUrls(large = "only-large")).plazaExtensions())
    }
}
