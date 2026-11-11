package ceui.pixiv.shaftapi

import ceui.lisa.BuildConfig
import java.net.InetAddress
import java.net.Proxy
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientVersionInterceptorTest {
    private fun client(notify: (String?) -> Unit) = client(ClientVersionInterceptor(notify))

    private fun client(interceptor: ClientVersionInterceptor) = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
        })
        .addInterceptor(interceptor)
        .build()

    @Test
    fun `default interceptor handles ordinary responses without an Android main dispatcher`() {
        MockWebServer().use { server ->
            val http = client(ClientVersionInterceptor())
            for (code in listOf(200, 401, 429, 500)) {
                val body = "response-$code"
                server.enqueue(MockResponse().setResponseCode(code).setBody(body))
                val url = server.url("/v1/config").newBuilder().host("pixshaft.com").build()
                http.newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals(code, it.code)
                    assertEquals(body, it.body!!.string())
                }
                assertEquals(BuildConfig.VERSION_CODE.toString(),
                    server.takeRequest().getHeader(ClientVersionInterceptor.VERSION_HEADER))
            }
        }
    }

    @Test
    fun `both first party hosts advertise version and preserve the 426 body`() {
        MockWebServer().use { server ->
            val notifications = mutableListOf<String?>()
            val http = client { notifications.add(it) }
            val body = """{"error":"upgrade_required","feature":"probe","minVersionName":"4.10.0"}"""
            for (host in listOf("pixshaft.com", "api.pixshaft.com")) {
                server.enqueue(MockResponse().setResponseCode(426).setBody(body))
                val url = server.url("/v1/probe/upgrade-required").newBuilder().host(host).build()
                http.newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals(426, it.code)
                    assertEquals(body, it.body!!.string())
                }
                assertEquals(BuildConfig.VERSION_CODE.toString(),
                    server.takeRequest().getHeader(ClientVersionInterceptor.VERSION_HEADER))
            }
            assertEquals(listOf("4.10.0", "4.10.0"), notifications)
        }
    }

    @Test
    fun `missing and malformed optional details still offer an upgrade`() {
        MockWebServer().use { server ->
            val notifications = mutableListOf<String?>()
            val http = client { notifications.add(it) }
            val bodies = listOf("", "<html>Upgrade required</html>", "{}",
                """{"minVersionName":null}""", """{"minVersionName":123}""",
                """{"minVersionName":{}}""", """{"minVersionName":"  "}""")
            for (body in bodies) {
                server.enqueue(MockResponse().setResponseCode(426).setBody(body))
                val url = server.url("/v1/config").newBuilder().host("pixshaft.com").build()
                http.newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals(body, it.body!!.string())
                }
            }
            assertEquals(bodies.map { null }, notifications)
        }
    }

    @Test
    fun `other HTTP errors and success keep their existing behavior`() {
        MockWebServer().use { server ->
            var notifications = 0
            val http = client { notifications++ }
            for (code in listOf(200, 401, 403, 404, 429, 500)) {
                val body = """{"error":"upgrade_required"}"""
                server.enqueue(MockResponse().setResponseCode(code).setBody(body))
                val url = server.url("/v1/config").newBuilder().host("pixshaft.com").build()
                http.newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals(code, it.code)
                    assertEquals(body, it.body!!.string())
                }
            }
            assertEquals(0, notifications)
        }
    }

    @Test
    fun `third party and COS hosts neither advertise version nor prompt`() {
        MockWebServer().use { server ->
            var notifications = 0
            val http = client { notifications++ }
            for (host in listOf("app-api.pixiv.net", "media.pixshaft.com", "pixshaft.com.example.org")) {
                server.enqueue(MockResponse().setResponseCode(426).setBody("{}"))
                val url = server.url("/").newBuilder().host(host).build()
                http.newCall(Request.Builder().url(url).build()).execute().close()
                assertNull(server.takeRequest().getHeader(ClientVersionInterceptor.VERSION_HEADER))
            }
            assertEquals(0, notifications)
        }
    }

    @Test
    fun `redirect to an unrelated host cannot trigger an app upgrade`() {
        MockWebServer().use { server ->
            var notifications = 0
            val http = client { notifications++ }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",
                server.url("/elsewhere").newBuilder().host("other.example.org").build()))
            server.enqueue(MockResponse().setResponseCode(426).setBody("{}"))
            val url = server.url("/").newBuilder().host("pixshaft.com").build()
            http.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(426, it.code)
            }
            assertEquals(0, notifications)
        }
    }
}
