package ceui.pixiv.safe.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConcurrentAuthenticatorTest {

    @Test
    fun `three simultaneous 401 responses share one refresh and all replay successfully`() {
        val staleArrivals = CountDownLatch(3)
        val staleRequests = AtomicInteger()
        val freshRequests = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.getHeader("Authorization")) {
                        "Bearer old" -> {
                            staleRequests.incrementAndGet()
                            staleArrivals.countDown()
                            check(staleArrivals.await(5, TimeUnit.SECONDS))
                            MockResponse()
                                .setResponseCode(401)
                                .addHeader("X-Pixshaft-Auth-Error", "access_token_expired")
                                .addHeader("X-Pixshaft-Auth-Refreshable", "true")
                        }
                        "Bearer new" -> {
                            freshRequests.incrementAndGet()
                            MockResponse().setResponseCode(200)
                        }
                        else -> MockResponse().setResponseCode(500)
                    }
                }
            }
            start()
        }
        val provider = CoordinatedProvider()
        val client = OkHttpClient.Builder()
            .addInterceptor(BearerInterceptor(provider))
            .authenticator(TokenAuthenticator(provider))
            .build()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)

        try {
            val calls = (1..3).map {
                pool.submit<Int> {
                    check(start.await(5, TimeUnit.SECONDS))
                    client.newCall(
                        Request.Builder().url(server.url("/v1/account/online")).build(),
                    ).execute().use { it.code }
                }
            }
            start.countDown()
            val statuses = calls.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(listOf(200, 200, 200), statuses)
            assertEquals(3, staleRequests.get())
            assertEquals(3, freshRequests.get())
            assertEquals(1, provider.refreshCalls.get())
        } finally {
            pool.shutdownNow()
            server.shutdown()
        }
    }

    private class CoordinatedProvider : SessionProvider {
        private val token = AtomicReference("old")
        val refreshCalls = AtomicInteger()
        private val coordinator = RefreshCoordinator(
            currentAccessToken = { token.get() },
            performRefresh = {
                refreshCalls.incrementAndGet()
                Thread.sleep(100)
                token.set("new")
                "new"
            },
        )

        override fun currentAccessToken(): String = token.get()

        override fun accessTokenOrBootstrap(): String = token.get()

        override fun refreshAfter401(staleAccessToken: String): String? =
            coordinator.refresh(staleAccessToken)

        override fun clearCurrentSession() {
            token.set("")
        }
    }
}
