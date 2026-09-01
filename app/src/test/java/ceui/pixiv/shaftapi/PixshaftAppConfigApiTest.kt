package ceui.pixiv.shaftapi

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * `GET /v1/config` 的线上契约。整套「服务端能远程关掉借号搜索」都压在两点上，破了都不会
 * 报错、只会静默走错分支，所以拿真 Retrofit + Gson 钉住：
 *   1. uid 作为 query 参数带出去（服务端靠它分灰度桶），未登录时**不带**该参数；
 *   2. 服务端没给的开关字段解析成 null（= 没意见，保留上次值），不能变成 false 把功能关掉。
 */
class PixshaftAppConfigApiTest {

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

    private fun enqueue(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    @Test
    fun `config call carries the caller uid and parses the switch`() = runBlocking {
        enqueue("""{"uid":123,"nana7miSearchEnabled":false,"nana7miRequestIdEnabled":true,"serverTime":1786939077135}""")

        val config = api.appConfig(123L, "github")

        val request = server.takeRequest()
        assertEquals("/v1/config?uid=123", request.path)
        assertEquals("github", request.getHeader("X-Shaft-Flavor"))
        assertEquals("GET", request.method)
        assertEquals(123L, config.uid)
        assertEquals(false, config.nana7miSearchEnabled)
        assertEquals(true, config.nana7miRequestIdEnabled)
        assertEquals(1786939077135L, config.serverTime)
    }

    @Test
    fun `a logged-out caller sends no uid at all`() = runBlocking {
        enqueue("""{"uid":null,"nana7miSearchEnabled":true,"serverTime":1}""")

        api.appConfig(null, "github")

        assertEquals("/v1/config", server.takeRequest().path)
    }

    @Test
    fun `a switch the server does not mention stays null instead of false`() = runBlocking {
        enqueue("""{"uid":123,"serverTime":1786939077135}""")

        val config = api.appConfig(123L, "github")

        assertNull(config.nana7miSearchEnabled)
        assertNull(config.nana7miRequestIdEnabled)
    }

    @Test
    fun `an unknown field does not break parsing`() = runBlocking {
        enqueue("""{"uid":123,"nana7miSearchEnabled":true,"somethingNewer":{"a":1}}""")

        assertEquals(true, api.appConfig(123L, "github").nana7miSearchEnabled)
    }

    @Test
    fun `the in-app push rides along and parses, and is null when absent`() = runBlocking {
        enqueue(
            """{"uid":123,"nana7miSearchEnabled":true,
                "plan":{"key":"pro","owned":"pro"},
                "push":{"id":7,"title":"Pro 专属","body":"正文","actionLabel":"去看看",
                        "actionUrl":"https://pixshaft.com/x","audience":"paid","startsAt":1,"endsAt":null},
                "serverTime":1}""",
        )
        val push = api.appConfig(123L, "github").push
        assertEquals(7L, push?.id)
        assertEquals("Pro 专属", push?.title)
        assertEquals("正文", push?.body)
        assertEquals("去看看", push?.actionLabel)
        assertEquals("https://pixshaft.com/x", push?.actionUrl)
        assertEquals("paid", push?.audience)
        assertNull(push?.endsAt)

        enqueue("""{"uid":123,"nana7miSearchEnabled":true,"push":null}""")
        assertNull(api.appConfig(123L, "github").push)
        enqueue("""{"uid":123,"nana7miSearchEnabled":true}""")
        assertNull(api.appConfig(123L, "github").push)
    }

    @Test
    fun `push ack posts uid and id, and settles on 2xx and 4xx but not on 5xx`() = runBlocking {
        enqueue("""{"ok":true,"id":7,"uid":123,"seen":true,"first":true}""")
        assertEquals(true, api.acknowledgeInAppPush(123L, 7L))
        val request = server.takeRequest()
        assertEquals("/v1/push/ack", request.path)
        assertEquals("POST", request.method)
        assertEquals("""{"uid":123,"id":7}""", request.body.readUtf8())

        // 404 = the push was deleted since it was served: nothing left to do.
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"unknown_push"}"""))
        assertEquals(true, api.acknowledgeInAppPush(123L, 7L))
        // 5xx = try again on the next cold start.
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(false, api.acknowledgeInAppPush(123L, 7L))
        // Nothing to ack for a bad key; never a request.
        assertEquals(true, api.acknowledgeInAppPush(0L, 7L))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a Lite caller explicitly marks the config request`() = runBlocking {
        enqueue("""{"uid":123,"nana7miSearchEnabled":false,"plan":null,"serverTime":1}""")

        val config = api.appConfig(123L, "google")

        val request = server.takeRequest()
        assertEquals("/v1/config?uid=123", request.path)
        assertEquals("google", request.getHeader("X-Shaft-Flavor"))
        assertEquals(false, config.nana7miSearchEnabled)
        assertNull(config.plan)
    }
}
