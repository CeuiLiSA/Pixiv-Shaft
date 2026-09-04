package ceui.pixiv.safe.auth

interface SessionProvider {
    fun currentAccessToken(): String?
    fun accessTokenOrBootstrap(): String?
    fun refreshAfter401(staleAccessToken: String): String?
    fun clearCurrentSession()
}
