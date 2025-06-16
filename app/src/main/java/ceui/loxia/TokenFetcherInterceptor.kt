package ceui.loxia

import ceui.pixiv.session.SessionManager
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import kotlin.Long

class TokenFetcherInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        return if (response.code == 400) {
            val gson = response.peekBody(Long.MAX_VALUE).string()
            if (gson.contains(ClientManager.TOKEN_ERROR_1) || gson.contains(ClientManager.TOKEN_ERROR_2)) {
                response.close()
                val tokenForThisRequest = request.header(ClientManager.HEADER_AUTH)
                    ?.substring(ClientManager.TOKEN_HEAD.length) ?: ""
                val refreshedAccessToken = try {
                    SessionManager.refreshAccessToken(tokenForThisRequest)
                } catch (ex: Exception) {
                    Timber.e(ex)
                    null
                }
                if (refreshedAccessToken != null) {
                    val newRequest = chain.request()
                        .newBuilder()
                        .header(ClientManager.HEADER_AUTH, ClientManager.TOKEN_HEAD + refreshedAccessToken)
                        .build()
                    chain.proceed(newRequest)
                } else {
                    response
                }
            } else {
                response
            }
        } else {
            response
        }
    }
}