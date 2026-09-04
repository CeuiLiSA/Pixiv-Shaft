package ceui.pixiv.safe.auth

public interface SessionProvider {
    public fun currentAccessToken(): String?
    public fun accessTokenOrBootstrap(): String?
    public fun refreshAfter401(staleAccessToken: String): String?
    public fun clearCurrentSession()
}
