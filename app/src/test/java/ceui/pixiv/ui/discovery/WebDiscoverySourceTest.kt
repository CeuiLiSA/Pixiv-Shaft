package ceui.pixiv.ui.discovery

import ceui.pixiv.api.PixivWebApi
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.utils.isFullDetail
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class WebDiscoverySourceTest {
    private lateinit var server: MockWebServer
    private lateinit var api: PixivWebApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(PixivWebApi::class.java)
    }

    @After fun tearDown() { server.shutdown() }

    private fun source(mode: WebDiscoveryMode = WebDiscoveryMode.ALL, loggedIn: Boolean = true) =
        WebDiscoverySource(api, mode, { loggedIn }) { IllustFeedItem(it) }

    private fun artwork(id: Long, restriction: Int = 0, extra: String = "") = """{
        "id":"$id", "userId":"321", "title":"Title", "userName":"Artist",
        "url":"https://i.pximg.net/c/250x250_80_a2/custom-thumb/img/2026/09/19/00/00/00/${id}_p0_custom1200.jpg",
        "width":600, "height":900, "pageCount":2, "illustType":1,
        "xRestrict":$restriction, "aiType":2, "tags":["landscape"] $extra
    }"""

    private fun enqueue(vararg works: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"error":false,"body":{"thumbnails":{"illust":[${works.joinToString(",")}]}}}"""))
    }

    @Test fun `short batches continue requesting the official source without page parameters`() = runBlocking {
        enqueue(artwork(1))
        enqueue(artwork(2))
        val source = source()
        val first = source.load(null)
        val second = source.load(first.nextCursor)
        assertNotNull(first.nextCursor)
        assertNotNull(second.nextCursor)
        assertEquals(listOf(1L, 2L), (first.items + second.items).map { it.feedKey })
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("/ajax/discovery/artworks", request.requestUrl!!.encodedPath)
            assertEquals(setOf("mode", "limit"), request.requestUrl!!.queryParameterNames)
            assertEquals("all", request.requestUrl!!.queryParameter("mode"))
            assertEquals("60", request.requestUrl!!.queryParameter("limit"))
        }
    }

    @Test fun `each age mode selects matching works including R18G`() = runBlocking {
        for ((mode, ids) in listOf(
            WebDiscoveryMode.ALL to listOf(1L, 2L, 3L),
            WebDiscoveryMode.SAFE to listOf(1L),
            WebDiscoveryMode.R18 to listOf(2L, 3L),
        )) {
            enqueue(artwork(1), artwork(2, 1), artwork(3, 2))
            assertEquals(ids, source(mode).load(null).items.map { it.feedKey })
            assertEquals(mode.apiValue, server.takeRequest().requestUrl!!.queryParameter("mode"))
        }
        assertEquals(WebDiscoveryMode.SAFE, WebDiscoveryMode.initial(filterR18 = true))
        assertEquals(WebDiscoveryMode.ALL, WebDiscoveryMode.initial(filterR18 = false))
    }

    @Test fun `web artwork retains filters and bookmark state without inventing original images`() = runBlocking {
        enqueue(artwork(1, 1, """, "bookmarkData":{"id":"9","private":false}"""), artwork(2))
        val items = source().load(null).items.map { (it as IllustFeedItem).illust }
        val first = items[0]
        assertTrue(first.is_bookmarked == true)
        assertFalse(items[1].is_bookmarked == true)
        assertEquals("Artist", first.user?.name)
        assertEquals(321L, first.user?.id)
        assertEquals(1, first.x_restrict)
        assertEquals(2, first.illust_ai_type)
        assertEquals(listOf("landscape"), first.tags?.map { it.name })
        assertEquals("manga", first.type)
        assertTrue(first.image_urls?.medium?.endsWith("1_p0_master1200.jpg") == true)
        assertFalse(first.isFullDetail())
        assertNull(first.image_urls?.original)
        assertNull(first.meta_single_page)
    }

    @Test fun `filtered batch keeps continuation but empty server batch ends`() = runBlocking {
        enqueue(artwork(1, 1))
        enqueue()
        val source = source(WebDiscoveryMode.SAFE)
        val first = source.load(null)
        assertTrue(first.items.isEmpty())
        assertNotNull(first.nextCursor)
        assertNull(source.load(first.nextCursor).nextCursor)
    }

    @Test fun `missing web session does not silently use anonymous recommendations`() = runBlocking {
        assertTrue(source(loggedIn = false).load(null).items.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun `business error and malformed success are failures not empty feeds`() = runBlocking {
        for (body in listOf("""{"error":true,"message":"Login required","body":null}""",
            """{"error":false,"body":{}}""")) {
            server.enqueue(MockResponse().setBody(body))
            try {
                source().load(null)
                fail("Expected a retryable failure")
            } catch (_: IOException) { }
        }
    }

    @Test fun `cancellation escapes mapping`() = runBlocking {
        enqueue(artwork(1))
        val cancelled = CancellationException("screen closed")
        val source = WebDiscoverySource(api, WebDiscoveryMode.ALL, { true }) { throw cancelled }
        try {
            source.load(null)
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            // Coroutine stack-trace recovery may copy the exception across dispatchers.
            assertEquals(cancelled.message, actual.message)
        }
    }
}
