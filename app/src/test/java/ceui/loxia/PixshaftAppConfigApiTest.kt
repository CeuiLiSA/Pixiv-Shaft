package ceui.loxia

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
        enqueue("""{"uid":123,"nana7miSearchEnabled":false,"serverTime":1786939077135}""")

        val config = api.appConfig(123L)

        val request = server.takeRequest()
        assertEquals("/v1/config?uid=123", request.path)
        assertEquals("GET", request.method)
        assertEquals(123L, config.uid)
        assertEquals(false, config.nana7miSearchEnabled)
        assertEquals(1786939077135L, config.serverTime)
    }

    @Test
    fun `a logged-out caller sends no uid at all`() = runBlocking {
        enqueue("""{"uid":null,"nana7miSearchEnabled":true,"serverTime":1}""")

        api.appConfig(null)

        assertEquals("/v1/config", server.takeRequest().path)
    }

    @Test
    fun `a switch the server does not mention stays null instead of false`() = runBlocking {
        enqueue("""{"uid":123,"serverTime":1786939077135}""")

        val config = api.appConfig(123L)

        assertNull(config.nana7miSearchEnabled)
    }

    @Test
    fun `an unknown field does not break parsing`() = runBlocking {
        enqueue("""{"uid":123,"nana7miSearchEnabled":true,"somethingNewer":{"a":1}}""")

        assertEquals(true, api.appConfig(123L).nana7miSearchEnabled)
    }
}
