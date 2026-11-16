package ceui.pixiv.session

import android.app.Application
import com.tencent.mmkv.MMKV
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

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
    }

    @Test
    fun `recordSuccess stores timestamp and success status`() {
        val uid = 123456L
        val timestamp = 1700000000000L

        assertEquals(0L, WebSessionManager.getLastSuccessTime(uid))
        assertEquals(WebSessionManager.STATUS_NONE, WebSessionManager.getStatus(uid))

        WebSessionManager.recordSuccess(uid, timestamp)

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
        WebSessionManager.recordFailure(uid, failTime)

        assertEquals(WebSessionManager.STATUS_FAILED, WebSessionManager.getStatus(uid))
        assertFalse(MemoryMMKV.values.containsKey(SessionManager.COOKIE_KEY))
        assertFalse(WebSessionManager.hasMatchingWebCookie(uid))
    }

    @Test
    fun `hasRecentSuccess returns true within 1 hour and false otherwise`() {
        val uid = 123456L
        val baseTime = 1700000000000L

        WebSessionManager.recordSuccess(uid, baseTime)

        // 30 分钟后：在 1 小时内
        assertTrue(WebSessionManager.hasRecentSuccess(uid, baseTime + 30 * 60 * 1000L))

        // 59 分钟后：在 1 小时内
        assertTrue(WebSessionManager.hasRecentSuccess(uid, baseTime + 59 * 60 * 1000L))

        // 60 分钟后：刚好或超过 1 小时
        assertFalse(WebSessionManager.hasRecentSuccess(uid, baseTime + 60 * 60 * 1000L))

        // 2 小时后：超过 1 小时
        assertFalse(WebSessionManager.hasRecentSuccess(uid, baseTime + 120 * 60 * 1000L))

        // 状态被置为失败后即使时间在 1 小时内也为 false
        WebSessionManager.recordFailure(uid, baseTime + 10 * 60 * 1000L)
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

        WebSessionManager.recordSuccess(uid1, 1000L)
        WebSessionManager.recordSuccess(uid2, 2000L)

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
        WebSessionManager.recordSuccess(uid, 1000L)

        assertTrue(WebSessionManager.hasMatchingWebCookie(uid))
        assertEquals(WebSessionManager.STATUS_SUCCESS, WebSessionManager.getStatus(uid))

        WebSessionManager.clear(uid)

        assertFalse(MemoryMMKV.values.containsKey(SessionManager.COOKIE_KEY))
        assertFalse(WebSessionManager.hasMatchingWebCookie(uid))
        assertEquals(0L, WebSessionManager.getLastSuccessTime(uid))
        assertEquals(WebSessionManager.STATUS_NONE, WebSessionManager.getStatus(uid))
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
