package ceui.pixiv.nana7mi.host

import ceui.lisa.model.ListIllust
import ceui.loxia.User
import ceui.pixiv.api.model.AccountResponse
import ceui.pixiv.login.InvalidRefreshTokenException
import ceui.pixiv.login.PixivOAuthResponse
import ceui.pixiv.login.PixivOAuthUser
import ceui.pixiv.nana7mi.session.AcquireResult
import ceui.pixiv.nana7mi.session.InMemoryAccountReportStore
import ceui.pixiv.nana7mi.session.Nana7miSessionEngine
import ceui.pixiv.nana7mi.session.NetworkRetryPolicy
import ceui.pixiv.nana7mi.session.RefreshedTokens
import ceui.pixiv.nana7mi.session.TokenRefreshOutcome
import ceui.pixiv.nana7mi.session.executeFirstPage
import ceui.pixiv.nana7mi.session.executeNextPage
import ceui.pixiv.nana7mi.session.isPaidRequestIdRejected
import ceui.pixiv.shaftapi.Nana7miPayload
import ceui.pixiv.shaftapi.Nana7miResult
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ShaftNana7miAdaptersTest {

    @Test
    fun `Nana7miResult maps all outcomes cleanly to AcquireResult with stable reason labels`() {
        val payload = Nana7miPayload(
            uid = 102L,
            account = AccountResponse(
                access_token = "at-102",
                refresh_token = "rt-102",
                user = User(id = 102L, is_premium = true),
            ),
            updatedAt = 1_000L,
            expiresAt = 2_000L,
            expired = false,
        )
        val success = Nana7miResult.Success(payload).toAcquireResult()
        assertTrue(success is AcquireResult.Success)
        assertEquals("success", success.reasonLabel)
        assertEquals("Bearer at-102", (success as AcquireResult.Success).account.authorizationHeader())

        assertEquals("disabled_for_lite", Nana7miResult.DisabledForLite.toAcquireResult().reasonLabel)
        assertEquals("no_account", Nana7miResult.NoAccount.toAcquireResult().reasonLabel)
        assertEquals("rate_limited", Nana7miResult.RateLimited(10L, "uid_5h", 200L, 100L).toAcquireResult().reasonLabel)
        assertEquals("http_409", Nana7miResult.HttpFailure(409).toAcquireResult().reasonLabel)
        assertTrue(Nana7miResult.HttpFailure(409).toAcquireResult().isPaidRequestIdRejected())
        assertFalse(Nana7miResult.HttpFailure(500).toAcquireResult().isPaidRequestIdRejected())
    }

    @Test
    fun `PixivAccountPayloadUpdater updates tokens and membership while preserving richer user profile`() {
        val original = AccountResponse(
            access_token = "old-at",
            refresh_token = "old-rt",
            expires_in = 100,
            scope = "public",
            user = User(id = 31660292L, is_premium = true, name = "meppoi"),
        )
        val tokens = RefreshedTokens.create {
            accessToken = "new-at"
            refreshToken = "new-rt"
            expiresInSeconds = 3600
            reportedUid = 31660292L
            reportedIsPremium = null
        }

        // null attributedPremium keeps stored membership untouched
        val kept = PixivAccountPayloadUpdater.applyRefresh(original, tokens, attributedPremium = null)
        assertEquals("new-at", kept.access_token)
        assertEquals("new-rt", kept.refresh_token)
        assertEquals(3600, kept.expires_in)
        assertEquals("public", kept.scope)
        assertEquals(true, kept.user?.is_premium)
        assertEquals("meppoi", kept.user?.name)

        // explicit false updates is_premium without erasing name
        val demoted = PixivAccountPayloadUpdater.applyRefresh(original, tokens, attributedPremium = false)
        assertEquals(false, demoted.user?.is_premium)
        assertEquals("meppoi", demoted.user?.name)

        // null user stays null
        assertNull(PixivAccountPayloadUpdater.mergeUserMembership(null, true))
    }

    @Test
    fun `PixivOAuthTokenRefresher distinguishes invalid refresh token from transient failures`() = runTest {
        val successRefresher = PixivOAuthTokenRefresher {
            PixivOAuthResponse(
                accessToken = "at",
                refreshToken = "rt",
                expiresIn = 3600,
                tokenType = "bearer",
                scope = "",
                user = PixivOAuthUser(102L, "n", "a", isPremium = true),
            )
        }
        val ok = successRefresher.refresh(102L, "old-rt")
        assertTrue(ok is TokenRefreshOutcome.Success)
        assertEquals(102L, (ok as TokenRefreshOutcome.Success).tokens.reportedUid)
        assertEquals(true, ok.tokens.reportedIsPremium)

        val revokedRefresher = PixivOAuthTokenRefresher {
            throw InvalidRefreshTokenException("Invalid refresh token")
        }
        assertTrue(revokedRefresher.refresh(102L, "old-rt") is TokenRefreshOutcome.InvalidRefreshToken)

        val transientRefresher = PixivOAuthTokenRefresher {
            throw RuntimeException("timeout")
        }
        assertTrue(transientRefresher.refresh(102L, "old-rt") is TokenRefreshOutcome.Failed)
    }

    @Test
    fun `PixivOAuthFailureClassifier matches only HTTP 400 with Pixiv OAuth expiry body`() {
        fun httpError(code: Int, body: String): HttpException {
            val response = Response.error<String>(
                code,
                body.toResponseBody("application/json".toMediaType()),
            )
            return HttpException(response)
        }

        assertTrue(
            PixivOAuthFailureClassifier.isAccessTokenExpired(
                httpError(400, """{"error":{"message":"Error occurred at the OAuth process"}}"""),
            ),
        )
        assertTrue(
            PixivOAuthFailureClassifier.isAccessTokenExpired(
                RuntimeException("wrapped", httpError(400, """{"error":"Invalid refresh token"}""")),
            ),
        )
        assertFalse(
            PixivOAuthFailureClassifier.isAccessTokenExpired(
                httpError(400, """{"error":"Invalid value"}"""),
            ),
        )
        assertFalse(
            PixivOAuthFailureClassifier.isAccessTokenExpired(
                httpError(401, """{"error":"Error occurred at the OAuth process"}"""),
            ),
        )
        assertFalse(PixivOAuthFailureClassifier.isAccessTokenExpired(IllegalStateException("other")))
    }

    @Test
    fun `terminal page for a lost borrowed account is safe to map and stops pagination`() {
        val page = ListIllust().apply { illusts = emptyList() }

        assertEquals(0, page.list.size)
        assertNull(page.nextUrl)
    }

    @Test
    fun `host adapters wire end-to-end first page, 400 OAuth reactive refresh, and pagination`() = runTest {
        val reportStore = InMemoryAccountReportStore<AccountResponse>()
        val initialPayload = Nana7miPayload(
            uid = 102L,
            account = AccountResponse(
                access_token = "stale-at",
                refresh_token = "old-rt",
                expires_in = 3600,
                scope = "public",
                user = User(id = 102L, is_premium = true, name = "lender"),
            ),
            updatedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60_000L,
            expired = false,
        )
        val engine = Nana7miSessionEngine.create<AccountResponse> {
            dispatcher = PixshaftAccountDispatcher { _, _ -> Nana7miResult.Success(initialPayload) }
            tokenRefresher = PixivOAuthTokenRefresher { rt ->
                assertEquals("old-rt", rt)
                PixivOAuthResponse(
                    accessToken = "fresh-at",
                    refreshToken = "fresh-rt",
                    expiresIn = 3600,
                    tokenType = "bearer",
                    scope = "public",
                    user = PixivOAuthUser(102L, "lender", "lender_acc", isPremium = null),
                )
            }
            payloadUpdater = PixivAccountPayloadUpdater
            this.reportStore = reportStore
            authFailureClassifier = PixivOAuthFailureClassifier
            retryPolicy = NetworkRetryPolicy.create { retryDelayMs = 0L }
        }
        val session = engine.newSession()
        val seenAuthHeaders = mutableListOf<String>()

        val first = session.executeFirstPage<AccountResponse, String> {
            requesterUid = 777L
            idempotencyEnabled = true
            fallback { error("unexpected fallback: ${it.code}") }
            request { account ->
                val header = account.authorizationHeader()
                seenAuthHeaders.add(header)
                if (header == "Bearer stale-at") {
                    throw HttpException(
                        Response.error<String>(
                            400,
                            """{"error":{"message":"Error occurred at the OAuth process"}}"""
                                .toResponseBody("application/json".toMediaType()),
                        ),
                    )
                }
                "page-1"
            }
        }

        val second = session.executeNextPage<AccountResponse, String> {
            requesterUid = 777L
            idempotencyEnabled = true
            directRequest { error("must not fall back to direct pagination") }
            endPagination { error("unexpected endPagination: $it") }
            request { account ->
                seenAuthHeaders.add(account.authorizationHeader())
                "page-2"
            }
        }

        assertEquals("page-1", first)
        assertEquals("page-2", second)
        assertEquals(listOf("Bearer stale-at", "Bearer fresh-at", "Bearer fresh-at"), seenAuthHeaders)
        // Rotated tokens persisted to report store while preserving richer User metadata & is_premium=true
        val persisted = reportStore.latestOnlinePayload(102L)!!
        assertEquals("fresh-at", persisted.access_token)
        assertEquals("fresh-rt", persisted.refresh_token)
        assertEquals(true, persisted.user?.is_premium)
        assertEquals("lender", persisted.user?.name)
    }
}
