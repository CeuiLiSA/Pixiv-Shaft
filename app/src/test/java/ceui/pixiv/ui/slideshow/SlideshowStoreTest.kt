package ceui.pixiv.ui.slideshow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlideshowStoreTest {

    @Test
    fun `put snapshots mutable input lists`() {
        val urls = mutableListOf("first")
        val titles = mutableListOf("title")
        val id = SlideshowStore.put(
            SlideshowStore.Session(urls, titles, startIndex = 0, random = false)
        )

        try {
            urls += "second"
            titles.clear()

            val stored = SlideshowStore.get(id)
            assertEquals(listOf("first"), stored?.urls)
            assertEquals(listOf("title"), stored?.titles)
        } finally {
            SlideshowStore.remove(id)
        }
    }

    @Test
    fun `abandoned sessions are bounded`() {
        val ids = List(9) { index ->
            SlideshowStore.put(
                SlideshowStore.Session(
                    urls = listOf("url-$index"),
                    titles = emptyList(),
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
