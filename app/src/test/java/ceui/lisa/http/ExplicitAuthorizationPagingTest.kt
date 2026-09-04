package ceui.lisa.http

import ceui.pixiv.api.API
import ceui.pixiv.network.HeaderInterceptor
import ceui.pixiv.network.TokenFetcherInterceptor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ExplicitAuthorizationPagingTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `next page keeps borrowed authorization and strips internal marker`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"illusts":[],"next_url":null}"""),
        )
        val api = createApi()

        val response = runBlocking { api.getNextIllustWithAuth(
            "Bearer borrowed-account",
            server.url("/v1/search/illust?offset=30").toString(),
        ) }

        val request = server.takeRequest()
        assertEquals("Bearer borrowed-account", request.getHeader("Authorization"))
        assertEquals(listOf("Bearer borrowed-account"), request.headers.values("Authorization"))
        assertNull(request.getHeader("X-Shaft-Explicit-Authorization"))
        assertEquals("/v1/search/illust?offset=30", request.path)
        assertEquals(0, response.illusts?.size ?: 0)
    }

    @Test
    fun `novel next page keeps borrowed authorization and strips internal marker`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"novels":[],"next_url":null}"""),
        )
        val api = createApi()

        val response = runBlocking { api.getNextNovelWithAuth(
            "Bearer borrowed-novel-account",
            server.url("/v1/search/novel?offset=30").toString(),
        ) }

        val request = server.takeRequest()
        assertEquals("Bearer borrowed-novel-account", request.getHeader("Authorization"))
        assertEquals(listOf("Bearer borrowed-novel-account"), request.headers.values("Authorization"))
        assertNull(request.getHeader("X-Shaft-Explicit-Authorization"))
        assertEquals("/v1/search/novel?offset=30", request.path)
        assertEquals(0, response.novels?.size ?: 0)
    }

    @Test
    fun `novel first page sends borrowed authorization and keeps query positions`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"novels":[],"next_url":null}"""),
        )
        val api = createApi()

        val response = runBlocking { api.searchNovelWithAuth(
            "Bearer borrowed-novel-account",
            "test word",
            "popular_desc",
            "2026-01-01",
            "2026-08-13",
            "partial_match_for_tags",
            100,
            999,
            3,
            "zh-cn",
            1,
            true,
            true,
            10,
            20,
            30,
            40,
            50,
            60,
        ) }

        val request = server.takeRequest()
        assertEquals("Bearer borrowed-novel-account", request.getHeader("Authorization"))
        assertEquals(listOf("Bearer borrowed-novel-account"), request.headers.values("Authorization"))
        assertNull(request.getHeader("X-Shaft-Explicit-Authorization"))
        assertEquals("/v1/search/novel", request.requestUrl?.encodedPath)
        assertEquals("test word", request.requestUrl?.queryParameter("word"))
        assertEquals("popular_desc", request.requestUrl?.queryParameter("sort"))
        assertEquals("100", request.requestUrl?.queryParameter("bookmark_num_min"))
        assertEquals("999", request.requestUrl?.queryParameter("bookmark_num_max"))
        assertEquals("3", request.requestUrl?.queryParameter("genre"))
        assertEquals("60", request.requestUrl?.queryParameter("reading_time_max"))
        assertEquals(0, response.novels?.size ?: 0)
    }

    @Test
    fun `borrowed authorization failure is not retried with current session`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"Error occurred at the OAuth process"}}"""),
        )
        val api = createApi()

        val error = runCatching {
            runBlocking {
                api.getNextIllustWithAuth(
                    "Bearer borrowed-account",
                    server.url("/v1/search/illust?offset=30").toString(),
                )
            }
        }.exceptionOrNull()

        assertTrue(error is HttpException)
        assertEquals(400, (error as HttpException).code())
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals(listOf("Bearer borrowed-account"), request.headers.values("Authorization"))
        assertNull(request.getHeader("X-Shaft-Explicit-Authorization"))
    }

    private fun createApi(): API {
        val client = OkHttpClient.Builder()
            // Mirror HeaderInterceptor's authorization branch without its Android-only headers.
            .addInterceptor { chain ->
                val original = chain.request()
                val request = if (HeaderInterceptor.shouldInjectSessionAuthorization(original)) {
                    original.newBuilder().header("Authorization", "Bearer current-user").build()
                } else {
                    original
                }
                chain.proceed(request)
            }
            .addInterceptor(TokenFetcherInterceptor())
            .build()
        return Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(API::class.java)
    }
}
