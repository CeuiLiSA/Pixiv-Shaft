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
    fun `nana7mi call posts an empty object and parses one account`() = runBlocking {
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

        val response = api.fetchNana7mi()
        val request = server.takeRequest()

        assertEquals("/v1/account/nana7mi", request.path)
        assertEquals("POST", request.method)
        assertEquals("{}", request.body.readUtf8())
        assertEquals(102L, response.uid)
        assertTrue(response.expired)
        assertEquals("access-102", response.account.access_token)
        assertEquals("refresh-102", response.account.refresh_token)
        assertNotNull(response.account.user)
    }
}
