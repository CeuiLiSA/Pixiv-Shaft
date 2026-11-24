package ceui.pixiv.nana7mi.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryAccountReportStoreTest {

    @Test
    fun `quarantine stores sha256 digest rather than raw refresh token and online persist clears it`() = runTest {
        val store = InMemoryAccountReportStore<String>()
        val rawSecret = "secret-refresh-token-value"

        assertTrue(store.persistInvalidRefreshToken(102L, rawSecret))
        val hash = store.quarantinedTokenHash(102L)
        assertNotNull(hash)
        assertEquals(64, hash!!.length)
        assertFalse(hash.contains(rawSecret))
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))

        // Persisting a fresh online payload supersedes stale invalidation for the same uid.
        assertTrue(store.persistOnline(102L, "refreshed-payload"))
        assertEquals("refreshed-payload", store.latestOnlinePayload(102L))
        assertNull(store.quarantinedTokenHash(102L))
        assertEquals(ReportDeliveryOutcome.DELIVERED, store.attemptOnline(102L))
    }

    @Test
    fun `rejects non-positive uid and blank refresh tokens`() = runTest {
        val store = InMemoryAccountReportStore<String>()

        assertFalse(store.persistOnline(0L, "payload"))
        assertFalse(store.persistInvalidRefreshToken(0L, "rt"))
        assertFalse(store.persistInvalidRefreshToken(102L, "   "))
        assertEquals(ReportDeliveryOutcome.QUEUED, store.attemptOnline(999L))
    }

    @Test
    fun `custom onDeliver callback controls delivery outcome`() = runTest {
        var deliveredUid: Long? = null
        val store = InMemoryAccountReportStore<String> { uid, _ ->
            deliveredUid = uid
            ReportDeliveryOutcome.QUEUED
        }
        store.persistOnline(42L, "p")

        assertEquals(ReportDeliveryOutcome.QUEUED, store.attemptOnline(42L))
        assertEquals(42L, deliveredUid)
    }
}
