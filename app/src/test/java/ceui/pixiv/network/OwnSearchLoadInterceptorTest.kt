package ceui.pixiv.network

import ceui.pixiv.actions.Nana7miSearchTelemetry.ContentType
import ceui.pixiv.actions.Nana7miSearchTelemetry.Page
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OwnSearchLoadInterceptorTest {

    private data class Reported(val uid: Long, val contentType: ContentType, val page: Page, val status: Int)

    private fun run(
        server: MockWebServer,
        path: String,
        premium: Boolean = true,
        uid: Long = 7L,
        explicitAuthorization: Boolean = false,
    ): List<Reported> {
        val reported = mutableListOf<Reported>()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                OwnSearchLoadInterceptor(
                    ownerUid = { uid },
                    isPremium = { premium },
                    report = { u, type, page, status -> reported += Reported(u, type, page, status) },
                ),
            )
            .build()
        val builder = Request.Builder().url(server.url(path))
        if (explicitAuthorization) builder.header(HeaderInterceptor.EXPLICIT_AUTHORIZATION_HEADER, "1")
        client.newCall(builder.build()).execute().close()
        return reported
    }

    @Test
    fun `every own-token search page is reported with its content type and page`() {
        MockWebServer().use { server ->
            repeat(5) { server.enqueue(MockResponse().setBody("{}")) }
            assertEquals(
                listOf(Reported(7L, ContentType.ILLUST, Page.FIRST, 200)),
                run(server, "/v1/search/illust?word=a&sort=popular_desc"),
            )
            assertEquals(
                listOf(Reported(7L, ContentType.ILLUST, Page.NEXT, 200)),
                run(server, "/v1/search/illust?word=a&offset=30"),
            )
            assertEquals(
                listOf(Reported(7L, ContentType.NOVEL, Page.FIRST, 200)),
                run(server, "/v1/search/novel?word=a&offset=0"),
            )
            assertEquals(
                listOf(Reported(7L, ContentType.ILLUST, Page.FIRST, 200)),
                run(server, "/v1/search/popular-preview/illust?word=a"),
            )
            assertEquals(
                listOf(Reported(7L, ContentType.NOVEL, Page.NEXT, 200)),
                run(server, "/v1/search/popular-preview/novel?word=a&offset=30"),
            )
        }
    }

    @Test
    fun `a page served through a self-hosted proxy prefix still counts`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            assertEquals(
                listOf(Reported(7L, ContentType.ILLUST, Page.NEXT, 200)),
                run(server, "/pixiv/v1/search/illust?word=a&offset=60"),
            )
        }
    }

    @Test
    fun `a response Pixiv refused is still load`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            assertEquals(
                listOf(Reported(7L, ContentType.ILLUST, Page.FIRST, 429)),
                run(server, "/v1/search/illust?word=a"),
            )
        }
    }

    @Test
    fun `borrowed, non-Premium, signed-out and non-search requests are not reported`() {
        MockWebServer().use { server ->
            repeat(6) { server.enqueue(MockResponse().setBody("{}")) }
            assertTrue(run(server, "/v1/search/illust?word=a", explicitAuthorization = true).isEmpty())
            assertTrue(run(server, "/v1/search/illust?word=a", premium = false).isEmpty())
            assertTrue(run(server, "/v1/search/illust?word=a", uid = 0L).isEmpty())
            assertTrue(run(server, "/v1/search/user?word=a").isEmpty())
            assertTrue(run(server, "/v1/search/options?word=a").isEmpty())
            assertTrue(run(server, "/v1/illust/ranking?mode=day").isEmpty())
        }
    }

    @Test
    fun `a request that never got a response is not load and still fails as before`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val reported = mutableListOf<Int>()
            val client = OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .addInterceptor(OwnSearchLoadInterceptor({ 7L }, { true }, { _, _, _, s -> reported += s }))
                .build()
            val thrown = runCatching {
                client.newCall(Request.Builder().url(server.url("/v1/search/illust?word=a")).build()).execute()
            }.exceptionOrNull()
            assertTrue(thrown is IOException)
            assertTrue(reported.isEmpty())
        }
    }

    @Test
    fun `a failing reporter never changes the response`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"illusts":[]}"""))
            val client = OkHttpClient.Builder()
                .addInterceptor(OwnSearchLoadInterceptor({ 7L }, { true }, { _, _, _, _ -> error("boom") }))
                .build()
            val body = client.newCall(Request.Builder().url(server.url("/v1/search/illust?word=a")).build())
                .execute().use { it.body?.string() }
            assertEquals("""{"illusts":[]}""", body)
        }
    }
}
