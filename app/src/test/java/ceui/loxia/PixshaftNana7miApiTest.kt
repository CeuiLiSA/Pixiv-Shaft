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
    fun `nana7mi refuses a dispatched account that lost premium`() = runBlocking {
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
                        "refresh_token": "refresh-102",
                        "user": { "id": 102, "is_premium": false }
                      },
                      "updatedAt": 1999990000000,
                      "expiresAt": 1999993300000,
                      "expired": false
                    }
                    """.trimIndent(),
                ),
        )

        val result = api.fetchNana7mi(31660292L)

        assertTrue(result is Nana7miResult.NotPremium)
        // The account travels with the result so the caller can report it back and let the server
        // reclassify the row instead of continuing to dispatch it.
        assertEquals(102L, (result as Nana7miResult.NotPremium).uid)
        assertEquals("refresh-102", result.account.refresh_token)
    }

    @Test
    fun `nana7mi treats a missing premium flag as unknown rather than lapsed`() = runBlocking {
        // Removing an account from the pool is disruptive for every other borrower, so only an
        // explicit `false` may do it. An absent field stays usable and lets Pixiv be the judge.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "uid": 103,
                      "account": {
                        "access_token": "access-103",
                        "refresh_token": "refresh-103",
                        "user": { "id": 103 }
                      },
                      "updatedAt": 1999990000000,
                      "expiresAt": 1999993300000,
                      "expired": false
                    }
                    """.trimIndent(),
                ),
        )

        assertTrue(api.fetchNana7mi(31660292L) is Nana7miResult.Success)
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

    @Test
    fun `invalid refresh report sends only uid and token hash`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true,"uid":102,"disabled":true}"""),
        )

        val ack = api.invalidateNana7mi(Nana7miInvalidReq(102L, "a".repeat(64)))
        val request = server.takeRequest()

        assertTrue(ack.ok)
        assertTrue(ack.disabled)
        assertEquals("/v1/account/nana7mi/invalid", request.path)
        assertEquals(
            "{\"uid\":102,\"refreshTokenHash\":\"${"a".repeat(64)}\"}",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `generic search telemetry contains no result summary`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"ok":true,"eventId":"123e4567-e89b-42d3-a456-426614174000","duplicate":false}""",
                ),
        )

        val ack = api.reportNana7miSearchTelemetry(
            Nana7miSearchTelemetryReq(
                eventId = "123e4567-e89b-42d3-a456-426614174000",
                flowId = "123e4567-e89b-42d3-a456-426614174001",
                eventType = "request",
                occurredAt = 2_000_000_000_000L,
                requesterUid = 31660292L,
                borrowedUid = 4867906L,
                query = "初音ミク",
                contentType = "novel",
                page = "next",
                route = "borrowed_official",
                outcome = "failure",
                flowOutcome = null,
                reason = "http_500",
                errorType = "HttpException",
                httpStatus = 500,
                durationMs = null,
                appVersion = "6.8.0-debug",
                appChannel = "github",
            ),
        )
        val request = server.takeRequest()

        assertTrue(ack.ok)
        assertEquals("/v1/account/nana7mi/telemetry", request.path)
        assertEquals(
            """{"eventId":"123e4567-e89b-42d3-a456-426614174000","flowId":"123e4567-e89b-42d3-a456-426614174001","eventType":"request","occurredAt":2000000000000,"requesterUid":31660292,"borrowedUid":4867906,"query":"初音ミク","contentType":"novel","page":"next","route":"borrowed_official","outcome":"failure","reason":"http_500","errorType":"HttpException","httpStatus":500,"appVersion":"6.8.0-debug","appChannel":"github"}""",
            request.body.readUtf8(),
        )
    }
}
