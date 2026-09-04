package ceui.pixiv.safe.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

public class BearerInterceptorTest {

    @Test
    public fun `protected route receives bearer and protocol version`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(BearerInterceptor(FakeProvider("access-token")))
                .build()

            client.newCall(
                Request.Builder().url(server.url("/v1/account/translate")).build(),
            ).execute().close()

            val request = server.takeRequest()
            assertEquals("Bearer access-token", request.getHeader("Authorization"))
            assertEquals("2", request.getHeader("X-Pixshaft-Auth-Version"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    public fun `public route neither bootstraps nor receives bearer`() {
        val provider = FakeProvider("access-token")
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(BearerInterceptor(provider))
                .build()

            client.newCall(Request.Builder().url(server.url("/v1/config")).build())
                .execute().close()

            assertNull(server.takeRequest().getHeader("Authorization"))
            assertEquals(0, provider.bootstrapCalls)
        } finally {
            server.shutdown()
        }
    }

    private class FakeProvider(private var token: String?) : SessionProvider {
        var bootstrapCalls: Int = 0

        override fun currentAccessToken(): String? = token

        override fun accessTokenOrBootstrap(): String? {
            bootstrapCalls++
            return token
        }

        override fun refreshAfter401(staleAccessToken: String): String? = null

        override fun clearCurrentSession() {
            token = null
        }
    }
}
