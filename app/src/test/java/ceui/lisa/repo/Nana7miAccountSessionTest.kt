package ceui.lisa.repo

import ceui.lisa.model.ListIllust
import ceui.loxia.AccountResponse
import ceui.loxia.Nana7miPayload
import com.google.gson.Gson
import io.reactivex.Observable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // Pagination must be able to tell "never borrowed" from "borrowed then lost".
        assertTrue(session.borrowedAccountLost)
    }

    /**
     * The empty page both repos hand back once a borrowed account is gone mid-pagination.
     *
     * `Mapper.apply` iterates `getList()` unconditionally, so the list must be set rather than left
     * null, and `getNextUrl()` must stay null so the feed stops asking for more instead of retrying
     * a premium-only cursor with the logged-in account.
     *
     * (The repos' own branch ordering can't be unit-tested here — constructing a `RemoteRepo` runs
     * `Common.showLog`, and `android.util.Log` is not mocked in JVM tests.)
     */
    @Test
    fun `terminal page for a lost borrowed account is safe to map and stops pagination`() {
        val page = ListIllust().apply { illusts = emptyList() }

        assertEquals(0, page.list.size)
        assertNull(page.nextUrl)
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
