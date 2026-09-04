package ceui.pixiv.safe.auth

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

public class TokenStoreTest {

    @Test
    public fun `refresh attempt is stable for one token generation`() {
        val values = FakeAuthKeyValueStore()
        val store = TokenStore(values, Gson())

        val first = store.refreshAttempt("session-1", 3L)
        val second = store.refreshAttempt("session-1", 3L)

        assertEquals(first, second)
        assertEquals(1, values.values.size)
    }

    @Test
    public fun `refresh attempt changes with its token generation`() {
        val store = TokenStore(FakeAuthKeyValueStore(), Gson())

        val first = store.refreshAttempt("session-1", 3L)
        val second = store.refreshAttempt("session-1", 4L)

        assertNotEquals(first, second)
    }

    @Test
    public fun `failed persistence never returns a new refresh attempt`() {
        val values = FakeAuthKeyValueStore()
        val store = TokenStore(values, Gson())
        val durable = store.refreshAttempt("session-1", 3L)
        values.rejectWrites = true

        assertThrows(IOException::class.java) {
            store.refreshAttempt("session-1", 4L)
        }

        values.rejectWrites = false
        assertEquals(durable, store.refreshAttempt("session-1", 3L))
    }

    private class FakeAuthKeyValueStore : AuthKeyValueStore {
        val values = mutableMapOf<String, String>()
        var rejectWrites: Boolean = false

        override fun decodeString(key: String): String? = values[key]

        override fun encodeString(key: String, value: String): Boolean {
            if (rejectWrites) return false
            values[key] = value
            return true
        }

        override fun removeValue(key: String) {
            values.remove(key)
        }

        override fun removeValues(keys: Array<String>) {
            keys.forEach(values::remove)
        }

        override fun sync() = Unit
    }
}
