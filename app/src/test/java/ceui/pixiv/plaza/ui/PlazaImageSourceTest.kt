package ceui.pixiv.plaza.ui

import ceui.pixiv.plaza.PlazaFailure
import ceui.pixiv.plaza.PlazaImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlazaImageSourceTest {
    private fun image(id: String, expired: Boolean = false) =
        PlazaImage(
            id,
            100,
            200,
            "image/jpeg",
            "https://media.pixshaft.com/$id?signature=old",
            if (expired) 0 else System.currentTimeMillis() + 300000,
        )

    @Test
    fun `valid signatures open without an API request`() = runTest {
        val initial = image("first")
        val source =
            PlazaImageSource(7, listOf(initial), 42, { 42 }) { error("Unexpected refresh") }
        assertEquals(initial, source.resolve(0))
    }

    @Test
    fun `adjacent expired pages refresh once and retain original media order`() = runTest {
        val initial = listOf(image("first", true), image("second", true))
        var calls = 0
        val source =
            PlazaImageSource(7, initial, 42, { 42 }) {
                calls++
                delay(10)
                listOf(image("second"), image("first"))
            }
        val results = initial.indices.map { async { source.resolve(it) } }.awaitAll()
        assertEquals(listOf("first", "second"), results.map { it.mediaId })
        assertEquals(1, calls)
    }

    @Test
    fun `a rejected signature refreshes once across concurrent retry requests`() = runTest {
        val initial = image("first")
        var calls = 0
        val source =
            PlazaImageSource(7, listOf(initial), 42, { 42 }) {
                calls++
                listOf(initial.copy(url = initial.url.replace("old", "new")))
            }
        repeat(2) { assertTrue(source.resolve(0, initial.url).url.endsWith("new")) }
        assertEquals(1, calls)
    }

    @Test
    fun `account changes stop refresh results from being displayed`() = runTest {
        var uid = 42L
        val source =
            PlazaImageSource(7, listOf(image("first", true)), 42, { uid }) {
                uid = 43
                listOf(image("first"))
            }
        try {
            source.resolve(0)
            fail("Account change must reject the image")
        } catch (_: PlazaFailure) {}
    }

    @Test
    fun `removed media cannot be replaced with another posts image position`() = runTest {
        val source =
            PlazaImageSource(7, listOf(image("removed", true)), 42, { 42 }) {
                listOf(image("different"))
            }
        try {
            source.resolve(0)
            fail("Removed media must fail instead of showing a different image")
        } catch (_: PlazaFailure) {}
    }
}
