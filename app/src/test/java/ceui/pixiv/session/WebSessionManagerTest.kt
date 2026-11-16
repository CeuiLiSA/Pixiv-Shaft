package ceui.pixiv.session

import android.app.Application
import androidx.lifecycle.MutableLiveData
import ceui.loxia.User
import ceui.pixiv.api.PixivWebApi
import ceui.pixiv.api.model.AccountResponse
import ceui.pixiv.api.model.WebResponse
import ceui.pixiv.api.model.WebUserDetail
import ceui.pixiv.network.WebHeaderInterceptor
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import retrofit2.HttpException
import java.io.IOException
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = Application::class,
    shadows = [WebSessionManagerTest.MemoryMMKV::class],
    instrumentedPackages = ["com.tencent.mmkv"],
)
class WebSessionManagerTest {

    @Before
    fun setUp() {
        MemoryMMKV.values.clear()
        ReflectionHelpers.getField<MutableLiveData<AccountResponse>>(SessionManager, "_loggedInAccount").value =
            AccountResponse(user = User(id = 123456L))
    }

    @Test
    fun `recordSuccess stores timestamp and success status`() {
        val uid = 123456L
        val timestamp = 1700000000000L

        assertEquals(0L, WebSessionManager.getLastSuccessTime(uid))
        assertEquals(WebSessionManager.STATUS_NONE, WebSessionManager.getStatus(uid))

        WebSessionManager.recordSuccess(uid, setCookie(uid), timestamp)

        assertEquals(timestamp, WebSessionManager.getLastSuccessTime(uid))
        assertEquals(WebSessionManager.STATUS_SUCCESS, WebSessionManager.getStatus(uid))
    }

    @Test
    fun `recordFailure stores failure status and clears web cookie`() {
        val uid = 123456L
        val cookie = "PHPSESSID=123456_validcookie"
        MemoryMMKV.values[SessionManager.COOKIE_KEY] = cookie

        assertTrue(WebSessionManager.hasMatchingWebCookie(uid))

        val failTime = 1700000001000L
        WebSessionManager.recordFailure(uid, cookie, failTime)

        assertEquals(WebSessionManager.STATUS_FAILED, WebSessionManager.getStatus(uid))
        assertFalse(MemoryMMKV.values.containsKey(SessionManager.COOKIE_KEY))
        assertFalse(WebSessionManager.hasMatchingWebCookie(uid))
    }

    @Test
    fun `hasRecentSuccess returns true within 1 hour and false otherwise`() {
        val uid = 123456L
        val baseTime = 1700000000000L

        val cookie = setCookie(uid)
        WebSessionManager.recordSuccess(uid, cookie, baseTime)

        // 30 分钟后：在 1 小时内
        assertTrue(WebSessionManager.hasRecentSuccess(uid, baseTime + 30 * 60 * 1000L))

        // 59 分钟后：在 1 小时内
        assertTrue(WebSessionManager.hasRecentSuccess(uid, baseTime + 59 * 60 * 1000L))

        // 60 分钟后：刚好或超过 1 小时
        assertFalse(WebSessionManager.hasRecentSuccess(uid, baseTime + 60 * 60 * 1000L))

        // 2 小时后：超过 1 小时
        assertFalse(WebSessionManager.hasRecentSuccess(uid, baseTime + 120 * 60 * 1000L))

        // 状态被置为失败后即使时间在 1 小时内也为 false
        WebSessionManager.recordFailure(uid, cookie, baseTime + 10 * 60 * 1000L)
        assertFalse(WebSessionManager.hasRecentSuccess(uid, baseTime + 20 * 60 * 1000L))
    }

    @Test
    fun `hasMatchingWebCookie validates account match`() {
        val uid1 = 123456L
        val uid2 = 654321L

        MemoryMMKV.values[SessionManager.COOKIE_KEY] = "PHPSESSID=123456_session"
        assertTrue(WebSessionManager.hasMatchingWebCookie(uid1))
        assertFalse(WebSessionManager.hasMatchingWebCookie(uid2))

        MemoryMMKV.values[SessionManager.COOKIE_KEY] = "PHPSESSID=anonymous"
        assertFalse(WebSessionManager.hasMatchingWebCookie(uid1))
    }

    @Test
    fun `purge removes only the target account session records`() {
        val uid1 = 111L
        val uid2 = 222L

        WebSessionManager.recordSuccess(uid1, setCookie(uid1), 1000L)
        WebSessionManager.recordSuccess(uid2, setCookie(uid2), 2000L)

        WebSessionManager.purge(uid1)

        assertEquals(0L, WebSessionManager.getLastSuccessTime(uid1))
        assertEquals(WebSessionManager.STATUS_NONE, WebSessionManager.getStatus(uid1))

        assertEquals(2000L, WebSessionManager.getLastSuccessTime(uid2))
        assertEquals(WebSessionManager.STATUS_SUCCESS, WebSessionManager.getStatus(uid2))
    }

    @Test
    fun `clear removes session records and clears web cookie`() {
        val uid = 123456L
        val cookie = "PHPSESSID=123456_validcookie"
        MemoryMMKV.values[SessionManager.COOKIE_KEY] = cookie
        WebSessionManager.recordSuccess(uid, cookie, 1000L)

        assertTrue(WebSessionManager.hasMatchingWebCookie(uid))
        assertEquals(WebSessionManager.STATUS_SUCCESS, WebSessionManager.getStatus(uid))

        WebSessionManager.clear(uid)

        assertFalse(MemoryMMKV.values.containsKey(SessionManager.COOKIE_KEY))
        assertFalse(WebSessionManager.hasMatchingWebCookie(uid))
        assertEquals(0L, WebSessionManager.getLastSuccessTime(uid))
        assertEquals(WebSessionManager.STATUS_NONE, WebSessionManager.getStatus(uid))
    }

    @Test
    fun `old session results cannot overwrite or clear a new login for the same account`() {
        val uid = 123456L
        val oldCookie = setCookie(uid)
        WebSessionManager.recordSuccess(uid, oldCookie, 1000L)
        val newCookie = setCookie(uid, "new")

        assertFalse(WebSessionManager.hasRecentSuccess(uid, 2000L))
        assertFalse(WebSessionManager.recordSuccess(uid, oldCookie, 2000L))
        assertFalse(WebSessionManager.recordFailure(uid, oldCookie, 2000L))
        assertEquals(newCookie, WebSessionManager.getCookie())
        assertEquals(1000L, WebSessionManager.getLastSuccessTime(uid))
    }

    @Test
    fun `failure and clearing another account preserve its cookie`() {
        val cookie = setCookie(654321L)
        assertFalse(WebSessionManager.recordFailure(123456L, cookie))
        WebSessionManager.clear(123456L)
        assertEquals(cookie, WebSessionManager.getCookie())
        assertEquals(WebSessionManager.STATUS_NONE, WebSessionManager.getStatus(123456L))
    }

    @Test
    fun `probe preserves credentials on offline server and generic authentication failures`() = runBlocking {
        val uid = 123456L
        val cookie = setCookie(uid)
        val failures = listOf(IOException("offline"), httpError(503), httpError(401))
        for (failure in failures) {
            assertEquals(WebSessionManager.CheckResult.UNAVAILABLE, WebSessionManager.check(uid, cookie, api { throw failure }))
            assertEquals(cookie, WebSessionManager.getCookie())
            assertEquals(WebSessionManager.STATUS_NONE, WebSessionManager.getStatus(uid))
        }
        val response = WebResponse<WebUserDetail>(error = true, message = "temporary error")
        assertEquals(WebSessionManager.CheckResult.UNAVAILABLE, WebSessionManager.check(uid, cookie, api { response }))
        assertEquals(cookie, WebSessionManager.getCookie())
    }

    @Test
    fun `probe clears only confirmed expired sessions and records success`() = runBlocking {
        val uid = 123456L
        val cookie = setCookie(uid)
        assertEquals(WebSessionManager.CheckResult.SUCCESS, WebSessionManager.check(uid, cookie, api {
            WebResponse(error = false, body = WebUserDetail(userId = uid.toString()))
        }))
        assertTrue(WebSessionManager.hasRecentSuccess(uid))
        assertEquals(WebSessionManager.CheckResult.EXPIRED, WebSessionManager.check(uid, cookie, api {
            throw httpError(401, """{"error":true,"message":"An unknown error occurred","body":[]}""")
        }))
        assertEquals("", WebSessionManager.getCookie())
        assertEquals(WebSessionManager.STATUS_FAILED, WebSessionManager.getStatus(uid))
    }

    @Test
    fun `probe drops results after relogin and propagates cancellation`() = runBlocking<Unit> {
        val uid = 123456L
        val cookie = setCookie(uid)
        assertEquals(WebSessionManager.CheckResult.STALE, WebSessionManager.check(uid, cookie, api {
            setCookie(uid, "new")
            throw httpError(401, """{"error":true,"message":"An unknown error occurred"}""")
        }))
        assertEquals("PHPSESSID=${uid}_new", WebSessionManager.getCookie())
        assertEquals(WebSessionManager.STATUS_NONE, WebSessionManager.getStatus(uid))
        assertThrows(CancellationException::class.java) {
            runBlocking { WebSessionManager.check(uid, WebSessionManager.getCookie(), api { throw CancellationException() }) }
        }
    }

    @Test
    fun `interceptor ignores anonymous requests and business failures without consuming the response`() {
        val uid = 123456L
        setCookie(uid)
        val success = """{"error":false,"body":[]}"""
        intercept(cookie = "", body = success)
        assertEquals(WebSessionManager.STATUS_NONE, WebSessionManager.getStatus(uid))
        intercept(body = """{"error":true,"body":[]}""")
        assertEquals(WebSessionManager.STATUS_NONE, WebSessionManager.getStatus(uid))
        assertEquals(success, intercept(body = success))
        assertTrue(WebSessionManager.hasRecentSuccess(uid))
    }

    @Test
    fun `interceptor cannot credit an old request to a replacement cookie`() {
        val uid = 123456L
        setCookie(uid)
        intercept(body = """{"error":false,"body":[]}""") { setCookie(uid, "replacement") }
        assertEquals(WebSessionManager.STATUS_NONE, WebSessionManager.getStatus(uid))
        assertEquals("PHPSESSID=${uid}_replacement", WebSessionManager.getCookie())
    }

    private fun setCookie(uid: Long, session: String = "session"): String = "PHPSESSID=${uid}_$session".also {
        MemoryMMKV.values[SessionManager.COOKIE_KEY] = it
    }

    private fun api(response: () -> WebResponse<WebUserDetail>): PixivWebApi = Proxy.newProxyInstance(
        PixivWebApi::class.java.classLoader, arrayOf(PixivWebApi::class.java),
    ) { _, method, _ ->
        check(method.name == "getWebUserDetail")
        response()
    } as PixivWebApi

    private fun httpError(code: Int, body: String = "gateway error"): HttpException =
        HttpException(retrofit2.Response.error<Any>(code, body.toResponseBody()))

    private fun intercept(cookie: String? = null, body: String, beforeResponse: () -> Unit = {}): String? {
        val client = OkHttpClient.Builder()
            .addInterceptor(WebHeaderInterceptor())
            .addInterceptor { chain ->
                beforeResponse()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body.toResponseBody()).build()
            }.build()
        val request = Request.Builder().url("https://www.pixiv.net/ajax/user/123456")
            .apply { if (cookie != null) header("Cookie", cookie) }.build()
        return client.newCall(request).execute().use { it.body?.string() }
    }

    @Implements(MMKV::class, isInAndroidSdk = false)
    class MemoryMMKV {
        @Implementation fun count(): Long = values.size.toLong()
        @Implementation fun containsKey(key: String): Boolean = values.containsKey(key)
        @Implementation fun removeValueForKey(key: String) { values.remove(key) }
        @Implementation fun decodeFloat(key: String, defaultValue: Float): Float = values[key] as? Float ?: defaultValue
        @Implementation fun decodeInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
        @Implementation fun decodeLong(key: String, defaultValue: Long): Long = values[key] as? Long ?: defaultValue
        @Implementation fun decodeBool(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
        @Implementation fun decodeString(key: String, defaultValue: String?): String? = values[key] as? String ?: defaultValue
        @Implementation fun getString(key: String, defaultValue: String?): String? = values[key] as? String ?: defaultValue
        @Implementation fun encode(key: String, value: Float): Boolean { values[key] = value; return true }
        @Implementation fun encode(key: String, value: Boolean): Boolean { values[key] = value; return true }
        @Implementation fun encode(key: String, value: String): Boolean { values[key] = value; return true }
        @Implementation fun encode(key: String, value: Int): Boolean { values[key] = value; return true }
        @Implementation fun encode(key: String, value: Long): Boolean { values[key] = value; return true }

        companion object {
            val values = mutableMapOf<String, Any>()

            @JvmStatic
            @Implementation
            fun mmkvWithID(id: String): MMKV = newInstance()

            @JvmStatic
            @Implementation
            fun defaultMMKV(): MMKV = newInstance()

            private fun newInstance(): MMKV = ReflectionHelpers.callConstructor(
                MMKV::class.java, ClassParameter.from(Long::class.javaPrimitiveType, 0L),
            )
        }
    }
}
