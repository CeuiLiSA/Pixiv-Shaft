package ceui.pixiv.auth

import ceui.lisa.BuildConfig
import ceui.pixiv.safe.auth.BearerInterceptor
import ceui.pixiv.safe.auth.TokenAuthenticator
import ceui.pixiv.session.SessionManager
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

internal object ShaftSourceAuthorization {
    private val authenticatedOrigin = BuildConfig.SHAFT_EVENTS_AUTH_ORIGIN.toHttpUrlOrNull()?.takeIf {
        BuildConfig.DEBUG && it.isHttps && it.encodedPath == "/" && it.encodedQuery == null &&
            it.encodedFragment == null && it.username.isEmpty() && it.password.isEmpty()
    }

    val baseUrl: String =
        authenticatedOrigin?.toString() ?: (BuildConfig.SHAFT_EVENTS_BASE_URL.trimEnd('/') + "/")

    fun grantFor(uid: Long): DebugAppGrant? =
        if (authenticatedOrigin == null) null else DebugAppAuthorization.forUid(uid)

    fun attachTo(builder: OkHttpClient.Builder, paths: (String) -> Boolean): OkHttpClient.Builder {
        val origin = authenticatedOrigin ?: return builder
        fun matches(url: HttpUrl): Boolean =
            url.isHttps && url.host == origin.host && url.port == origin.port && paths(url.encodedPath) &&
                grantFor(SessionManager.loggedInUid) != null
        return builder.followRedirects(false)
            .addInterceptor(BearerInterceptor(AuthSessionManager, ::matches))
            .authenticator(TokenAuthenticator(AuthSessionManager, ::matches))
    }
}
