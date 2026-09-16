package ceui.pixiv.safe.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PlazaAuthenticationTest {
    @Test
    fun `every plaza route attaches Tokyo credentials and an expired token refreshes once`() {
        MockWebServer().use { server ->
            server.start()
            var refreshes = 0
            val sessions =
                object : SessionProvider {
                    override fun currentAccessToken() = "tokyo-old"

                    override fun accessTokenOrBootstrap() = "tokyo-old"

                    override fun refreshAfter401(staleAccessToken: String): String {
                        refreshes++
                        return "tokyo-new"
                    }

                    override fun clearCurrentSession() {}
                }
            val client =
                OkHttpClient.Builder()
                    .addInterceptor(BearerInterceptor(sessions))
                    .authenticator(TokenAuthenticator(sessions))
                    .build()
            for ((method, path) in
                listOf(
                    "GET" to "/v1/plaza/posts",
                    "POST" to "/v1/plaza/posts",
                    "GET" to "/v1/plaza/posts/1",
                    "PUT" to "/v1/plaza/posts/1/like",
                    "DELETE" to "/v1/plaza/posts/1/like",
                    "DELETE" to "/v1/plaza/posts/1",
                )) {
                server.enqueue(MockResponse().setBody("{}"))
                client
                    .newCall(
                        Request.Builder()
                            .url(server.url(path))
                            .method(
                                method,
                                if (method == "POST" || method == "PUT") "{}".toRequestBody()
                                else null,
                            )
                            .build()
                    )
                    .execute()
                    .close()
                assertEquals("Bearer tokyo-old", server.takeRequest().getHeader("Authorization"))
            }
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .addHeader("X-Pixshaft-Auth-Error", "access_token_expired")
                    .addHeader("X-Pixshaft-Auth-Refreshable", "true")
            )
            server.enqueue(MockResponse().setBody("{}"))
            client
                .newCall(Request.Builder().url(server.url("/v1/plaza/posts")).build())
                .execute()
                .use { assertEquals(200, it.code) }
            assertEquals("Bearer tokyo-old", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer tokyo-new", server.takeRequest().getHeader("Authorization"))
            assertEquals(1, refreshes)
            server.enqueue(MockResponse())
            client.newCall(Request.Builder().url(server.url("/health")).build()).execute().close()
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }
}
