package ceui.pixiv.ui.slideshow

import ceui.pixiv.api.model.Illust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlideshowStoreTest {

    private fun slide(url: String) =
        SlideshowStore.Slide(url = url, title = "title", illust = Illust(id = 42L), page = 0)

    @Test
    fun `put snapshots mutable input lists`() {
        val slides = mutableListOf(slide("first"))
        val id = SlideshowStore.put(
            SlideshowStore.Session(slides, startIndex = 0, random = false)
        )

        try {
            slides += slide("second")

            val stored = SlideshowStore.get(id)
            assertEquals(listOf("first"), stored?.slides?.map { it.url })
            assertEquals(listOf("title"), stored?.slides?.map { it.title })
        } finally {
            SlideshowStore.remove(id)
        }
    }

    @Test
    fun `abandoned sessions are bounded`() {
        val ids = List(9) { index ->
            SlideshowStore.put(
                SlideshowStore.Session(
                    slides = listOf(slide("url-$index")),
                    startIndex = 0,
                    random = false,
                )
            )
        }

        try {
            assertNull(SlideshowStore.get(ids.first()))
        } finally {
            ids.forEach(SlideshowStore::remove)
        }
    }
}
