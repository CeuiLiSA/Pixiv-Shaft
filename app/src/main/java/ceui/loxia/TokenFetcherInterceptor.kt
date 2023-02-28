package ceui.loxia

import android.text.TextUtils
import ceui.lisa.activities.Shaft
import ceui.lisa.fragments.FragmentLogin
import ceui.lisa.utils.Local
import okhttp3.Interceptor
import okhttp3.Response
import kotlin.Exception
import kotlin.Long
import kotlin.String

class TokenFetcherInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        return if (response.code == 400) {
            val gson = response.peekBody(Long.MAX_VALUE).string()
            if (gson.contains(ClientManager.TOKEN_ERROR_1) || gson.contains(ClientManager.TOKEN_ERROR_2)) {
                response.close()
                val oldToken = request.header(ClientManager.HEADER_AUTH)
                    ?.substring(ClientManager.TOKEN_HEAD.length) ?: ""
                val accessToken = refreshToken(oldToken)
                if (accessToken != null) {
                    val newRequest = chain.request()
                        .newBuilder()
                        .header(ClientManager.HEADER_AUTH, accessToken)
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

    @Synchronized
    private fun refreshToken(oldToken: String): String? {
        if (!TextUtils.equals(Shaft.sUserModel.access_token, oldToken)) {
            return Shaft.sUserModel.access_token
        }

        val refreshToken = Shaft.sUserModel.refresh_token ?: ""
        try {
            val accountResponse = Client.authApi.newRefreshToken(
                FragmentLogin.CLIENT_ID,
                FragmentLogin.CLIENT_SECRET,
                FragmentLogin.REFRESH_TOKEN,
                refreshToken,
                true
            ).execute().body()
            if (accountResponse != null) {
                Local.saveUser(accountResponse)
                return accountResponse.access_token
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }
}