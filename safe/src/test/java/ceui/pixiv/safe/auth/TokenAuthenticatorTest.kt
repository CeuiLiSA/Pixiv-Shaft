package ceui.pixiv.safe.auth

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

public class TokenAuthenticatorTest {

    @Test
    public fun `refreshable account 401 renews and replays with fresh bearer`() {
        val provider = FakeProvider(current = "old", refreshed = "new")
        val response = unauthorized(
            request("https://pixshaft.com/v1/account/translate", "old"),
            reason = "access_token_expired",
            refreshable = true,
        )

        val replay = TokenAuthenticator(provider).authenticate(null, response)

        assertEquals("Bearer new", replay?.header("Authorization"))
        assertEquals("2", replay?.header("X-Pixshaft-Auth-Version"))
        assertEquals(1, provider.refreshCalls)
    }

    @Test
    public fun `second 401 is terminal and cannot loop`() {
        val provider = FakeProvider(current = "old", refreshed = "new")
        val first = unauthorized(
            request("https://pixshaft.com/v1/account/translate", "old"),
            "access_token_expired",
            true,
        )
        val second = unauthorized(
            request("https://pixshaft.com/v1/account/translate", "new"),
            "access_token_invalid",
            true,
            prior = first,
        )

        assertNull(TokenAuthenticator(provider).authenticate(null, second))
        assertEquals(0, provider.refreshCalls)
    }

    @Test
    public fun `revoked session is cleared without refresh`() {
        val provider = FakeProvider(current = "old", refreshed = "new")
        val response = unauthorized(
            request("https://pixshaft.com/v1/account/translate", "old"),
            "session_revoked",
            false,
        )

        assertNull(TokenAuthenticator(provider).authenticate(null, response))
        assertEquals(1, provider.clearCalls)
        assertEquals(0, provider.refreshCalls)
    }

    @Test
    public fun `missing session can bootstrap after auth-required 401`() {
        val provider = FakeProvider(current = null, bootstrap = "first")
        val response = unauthorized(
            request("https://pixshaft.com/v1/account/translate"),
            "auth_required",
            false,
        )

        val replay = TokenAuthenticator(provider).authenticate(null, response)

        assertEquals("Bearer first", replay?.header("Authorization"))
        assertEquals(1, provider.bootstrapCalls)
    }

    @Test
    public fun `public route 401 is never treated as a session failure`() {
        val provider = FakeProvider(current = "old", refreshed = "new")
        val response = unauthorized(
            request("https://pixshaft.com/v1/config", "old"),
            "access_token_expired",
            true,
        )

        assertNull(TokenAuthenticator(provider).authenticate(null, response))
        assertEquals(0, provider.refreshCalls)
    }

    private fun request(url: String, token: String? = null): Request {
        val builder = Request.Builder().url(url)
        if (token != null) builder.header("Authorization", "Bearer $token")
        return builder.build()
    }

    private fun unauthorized(
        request: Request,
        reason: String,
        refreshable: Boolean,
        prior: Response? = null,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(401)
        .message("Unauthorized")
        .header("X-Pixshaft-Auth-Error", reason)
        .header("X-Pixshaft-Auth-Refreshable", refreshable.toString())
        .priorResponse(prior)
        .build()

    private class FakeProvider(
        var current: String?,
        private val refreshed: String? = null,
        private val bootstrap: String? = null,
    ) : SessionProvider {
        var refreshCalls: Int = 0
        var bootstrapCalls: Int = 0
        var clearCalls: Int = 0

        override fun currentAccessToken(): String? = current

        override fun accessTokenOrBootstrap(): String? {
            bootstrapCalls++
            current = bootstrap
            return bootstrap
        }

        override fun refreshAfter401(staleAccessToken: String): String? {
            refreshCalls++
            current = refreshed
            return refreshed
        }

        override fun clearCurrentSession() {
            clearCalls++
            current = null
        }
    }
}
