package ceui.pixiv.shaftapi

import ceui.lisa.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslateUserAgentInterceptorTest {
    @Test
    fun `runtime UA with a non ASCII model is safe in HTTP headers`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val client = OkHttpClient.Builder()
                .addInterceptor(TranslateUserAgentInterceptor { "Dalvik/2.1.0 (Android 15; 设备)" })
                .build()
            client.newCall(Request.Builder().url(server.url("/v1/account/translate"))
                .build()).execute().close()
            assertEquals("Dalvik/2.1.0 (Android 15; \\u8bbe\\u5907)", server.takeRequest().getHeader("User-Agent"))
        }
    }

    @Test
    fun `translation sends runtime UA without changing signed payload`() {
        val runtimeUa = "Dalvik/2.1.0 (Linux; U; Android 15; Pixel 9 Build/AP3A.241105.008)"
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val client = OkHttpClient.Builder()
                .addInterceptor(TranslateUserAgentInterceptor { runtimeUa })
                .build()
            val body = """{"uid":7,"texts":["hello"],"lang":"zh-CN"}"""
            client.newCall(Request.Builder().url(server.url("/v1/account/translate"))
                .header("X-Shaft-Sign", "signed-body")
                .post(body.toRequestBody()).build()).execute().close()
            val request = server.takeRequest()
            assertEquals(runtimeUa, request.getHeader("User-Agent"))
            assertEquals("3", request.getHeader("X-Shaft-Translate-Version"))
            assertEquals("signed-body", request.getHeader("X-Shaft-Sign"))
            assertEquals(body, request.body.readUtf8())
        }
    }

    @Test
    fun `config and quota advertise the same capability without changing their UA`() {
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder()
                .addInterceptor(TranslateUserAgentInterceptor { error("must not read UA") })
                .build()
            for (path in listOf("/v1/config", "/v1/account/translate/quota")) {
                server.enqueue(MockResponse().setBody("{}"))
                client.newCall(Request.Builder().url(server.url(path))
                    .header("User-Agent", "existing-agent")
                    .build()).execute().close()
                val request = server.takeRequest()
                assertEquals("existing-agent", request.getHeader("User-Agent"))
                assertEquals("3", request.getHeader("X-Shaft-Translate-Version"))
            }
        }
    }

    @Test
    fun `unrelated endpoints do not advertise translation capability`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val client = OkHttpClient.Builder()
                .addInterceptor(TranslateUserAgentInterceptor { error("must not read UA") })
                .build()
            client.newCall(Request.Builder().url(server.url("/v1/history/7")).build()).execute().close()
            assertEquals(null, server.takeRequest().getHeader("X-Shaft-Translate-Version"))
        }
    }

    @Test
    fun `missing runtime UA uses the actual application version`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val client = OkHttpClient.Builder()
                .addInterceptor(TranslateUserAgentInterceptor { null })
                .build()
            client.newCall(Request.Builder().url(server.url("/v1/account/translate"))
                .build()).execute().close()
            assertEquals("PixShaft/${BuildConfig.VERSION_NAME}", server.takeRequest().getHeader("User-Agent"))
        }
    }
}
