package ceui.lisa.repo

import ceui.loxia.AccountResponse
import ceui.loxia.Nana7miPayload
import com.google.gson.Gson
import io.reactivex.Observable
import org.junit.Assert.assertEquals
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

    /**
     * Guards the contract [Nana7miAccountSession.renew] depends on: Pixiv's raw token response
     * deserialises straight into [AccountResponse], and its `user.is_premium` is the fresh
     * membership signal that the library's own `PixivOAuthResponse.user` throws away.
     *
     * Note `id` arrives as a JSON **string** — this is what the endpoint really sends.
     */
    @Test
    fun `pixiv token response exposes current membership`() {
        val rawBody = """
            {
              "access_token": "redacted-access",
              "expires_in": 3600,
              "token_type": "bearer",
              "scope": "",
              "refresh_token": "redacted-refresh",
              "user": {
                "id": "31660292",
                "name": "meppoi",
                "account": "meppoi",
                "is_premium": false,
                "x_restrict": 2,
                "is_mail_authorized": true
              }
            }
        """.trimIndent()

        val parsed = Gson().fromJson(rawBody, AccountResponse::class.java)

        assertEquals("redacted-refresh", parsed.refresh_token)
        assertEquals(31660292L, parsed.user?.id)
        assertEquals(false, parsed.user?.is_premium)
    }
}
