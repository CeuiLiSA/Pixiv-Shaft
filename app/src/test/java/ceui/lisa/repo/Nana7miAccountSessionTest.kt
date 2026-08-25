package ceui.lisa.repo

import ceui.lisa.model.ListIllust
import ceui.loxia.AccountResponse
import ceui.loxia.Nana7miPayload
import ceui.loxia.Nana7miResult
import ceui.loxia.User
import ceui.pixiv.actions.AccountOnlineReportOutbox
import ceui.pixiv.login.PixivOAuthUser
import io.reactivex.Observable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Nana7miAccountSessionTest {

    /** Outbox whose Context is never resolved: these tests stop before any persistence. */
    private fun testOutbox() = AccountOnlineReportOutbox(lazy { error("no Context in unit test") })


    @Test
    fun `disabled flavor is not reported as an empty account pool`() {
        val session = Nana7miAccountSession(testOutbox())

        assertEquals("disabled_for_lite", session.resultLabel(Nana7miResult.DisabledForLite))
        assertEquals("no_account", session.resultLabel(Nana7miResult.NoAccount))
    }

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
        val session = Nana7miAccountSession(testOutbox())
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
     * Guards the rule [Nana7miAccountSession.renew] applies to the membership it gets back from
     * a token refresh: **overwrite only when pixiv actually said something.**
     *
     * pixiv omits `is_premium` from some refresh responses. Before, that arrived as `false` and
     * the renew path wrote it straight into the stored account — a paying account demoted to free
     * on the strength of a field the server never sent, which drops it out of the lending pool.
     * `PixivOAuthUser.isPremium` is nullable precisely so the two cases stay distinguishable, and
     * renew leans on that: `null` keeps the stored value, non-null replaces it.
     */
    @Test
    fun `membership is replaced only when the refresh response reports it`() {
        val stored = User(id = 31660292L, is_premium = true, name = "meppoi")

        // pixiv said nothing → the stored membership survives untouched
        assertEquals(true, mergeMembership(stored, null)?.is_premium)
        // pixiv said "not a member" → that is a real answer, take it
        assertEquals(false, mergeMembership(stored, false)?.is_premium)
        assertEquals(true, mergeMembership(stored, true)?.is_premium)
        // and only that one field moves — the richer stored profile survives
        assertEquals("meppoi", mergeMembership(stored, false)?.name)
        assertNull(mergeMembership(null, true))
    }

    /**
     * The other half of that rule: a response whose user cannot be matched to the account being
     * renewed must not touch it either. `uid` is guaranteed `> 0` upstream, and pixiv-login falls
     * back to `0` when pixiv omits or mangles the id, so the equality check also rejects
     * unidentifiable responses rather than trusting them.
     *
     * The stake is higher than one stored field: `renew` also tests this value with `== false` to
     * decide whether to return [ceui.loxia.Nana7miResult.NotPremium], which pauses dispatch and
     * pulls the account out of the lending pool. Anything that is *not* an explicit `false` has to
     * stay `null` so that branch is never taken on a guess.
     */
    @Test
    fun `membership from a mismatched or unidentifiable user is ignored`() {
        val uid = 31660292L

        assertEquals(
            false,
            freshMembershipOf(PixivOAuthUser(uid, "n", "a", isPremium = false), uid),
        )
        // different account
        assertNull(freshMembershipOf(PixivOAuthUser(999L, "n", "a", isPremium = false), uid))
        // id absent or non-numeric → pixiv-login reports 0
        assertNull(freshMembershipOf(PixivOAuthUser(0L, "n", "a", isPremium = false), uid))
        // pixiv omitted the whole user, or its shape changed and the library dropped it
        assertNull(freshMembershipOf(null, uid))
    }
}
