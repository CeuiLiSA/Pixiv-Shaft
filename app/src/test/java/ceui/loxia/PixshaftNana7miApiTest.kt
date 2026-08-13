package ceui.loxia

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class PixshaftNana7miApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: PixshaftApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(Gson()))
            .build()
            .create(PixshaftApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `nana7mi call posts caller uid and parses one account`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "uid": 102,
                      "account": {
                        "access_token": "access-102",
                        "expires_in": 3600,
                        "refresh_token": "refresh-102",
                        "scope": "public",
                        "token_type": "bearer",
                        "user": { "id": 102 }
                      },
                      "updatedAt": 1999990000000,
                      "expiresAt": 1999993300000,
                      "expired": true
                    }
                    """.trimIndent(),
                ),
        )

        val result = api.fetchNana7mi(31660292L)
        val request = server.takeRequest()

        assertEquals("/v1/account/nana7mi", request.path)
        assertEquals("POST", request.method)
        assertEquals("{\"uid\":31660292}", request.body.readUtf8())
        assertTrue(result is Nana7miResult.Success)
        val response = (result as Nana7miResult.Success).value
        assertEquals(102L, response.uid)
        assertTrue(response.expired)
        assertEquals("access-102", response.account.access_token)
        assertEquals("refresh-102", response.account.refresh_token)
        assertNotNull(response.account.user)
    }

    @Test
    fun `nana7mi maps no account and rate limit without throwing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":\"no_account\"}"))
        assertTrue(api.fetchNana7mi(1L) is Nana7miResult.NoAccount)

        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "17")
                .setBody("{\"error\":\"rate_limited\"}"),
        )
        val limited = api.fetchNana7mi(2L)
        assertTrue(limited is Nana7miResult.RateLimited)
        assertEquals(17L, (limited as Nana7miResult.RateLimited).retryAfterSeconds)
    }

    @Test
    fun `nana7mi rejects malformed success without throwing`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"uid\":102,\"expired\":false}"),
        )

        assertTrue(api.fetchNana7mi(3L) is Nana7miResult.InvalidResponse)
        assertTrue(api.fetchNana7mi(0L) is Nana7miResult.InvalidRequest)
    }
}
