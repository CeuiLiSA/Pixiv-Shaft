package ceui.lisa.repo

import ceui.loxia.AccountResponse
import ceui.loxia.Nana7miPayload
import io.reactivex.Observable
import org.junit.Assert.assertNull
import org.junit.Test

class Nana7miAccountSessionTest {

    @Test
    fun `renewal failure clears borrowed payload before preview fallback`() {
        val stale = Nana7miPayload(
            uid = 42L,
            account = AccountResponse(
                access_token = "expired-access",
                refresh_token = null,
            ),
            updatedAt = 0L,
            expiresAt = 0L,
            expired = false,
        )
        val session = Nana7miAccountSession()
        // fetchReady normally installs this state; avoid networking in this focused state test.
        Nana7miAccountSession::class.java.getDeclaredField("payload").apply {
            isAccessible = true
            set(session, stale)
        }

        val observer = Nana7miSearchSerial.run("renewal_failure") { lease ->
            session.requestWithRefresh(
                initial = stale,
                stage = "test",
                lease = lease,
                successDetails = { _: String -> "unexpected" },
                request = { Observable.just("unexpected") },
            )
        }.test()

        observer.assertError { it is BorrowedAccountUnavailableException }
        assertNull(session.payload)
    }
}
