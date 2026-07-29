package ceui.lisa.fragments

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RecmdUserHandoffTest {

    @Test
    fun `snapshot can only be taken once`() {
        val snapshot = RecmdUserSnapshot(emptyList(), null)
        val key = RecmdUserHandoff.put(snapshot)

        assertSame(snapshot, RecmdUserHandoff.take(key))
        assertNull(RecmdUserHandoff.take(key))
    }

    @Test
    fun `abandoned snapshots are bounded`() {
        val keys = List(9) {
            RecmdUserHandoff.put(RecmdUserSnapshot(emptyList(), null))
        }

        try {
            assertNull(RecmdUserHandoff.take(keys.first()))
        } finally {
            keys.forEach { RecmdUserHandoff.discard(it) }
        }
    }
}
