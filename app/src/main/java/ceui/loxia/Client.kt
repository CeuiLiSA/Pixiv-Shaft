package ceui.loxia

import android.content.Context
import ceui.lisa.http.AccountTokenApi
import ceui.lisa.http.AppApi
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object Client {

    private val clientManager = ClientManager()

    val appApi: API by lazy {
        clientManager.createAPPAPI(API::class.java)
    }

    val authApi: AccountTokenApi by lazy {
        clientManager.createOAuthAPI(AccountTokenApi::class.java)
    }
}

class ClientManager {

    companion object {
        const val APP_API_HOST = "https://app-api.pixiv.net"
        const val OAUTH_HOST = "https://oauth.secure.pixiv.net"
        const val WEB_API_HOST = "https://www.pixiv.net"
        const val NETEASY_API_HOST = "http://192.243.123.124:3000"

        const val CLIENT_ID = "KzEZED7aC0vird8jWyHM38mXjNTY"
        const val CLIENT_SECRET = "W9JZoJe00qPvJsiyCGT3CCtC6ZUtdpKpzMbNlUGP"
        const val GRANT_REFRESH_TOKEN = "refresh_token"
        const val GRANT_AUTH_CODE = "authorization_code"

        const val CALLBACK_LINK = "https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback"

        const val TOKEN_HEAD = "Bearer "

        const val HEADER_AUTH = "authorization"

        const val REQUIEST_TIME = 10L

        const val TOKEN_ERROR_1 = "Error occurred at the OAuth process"
        const val TOKEN_ERROR_2 = "Invalid refresh token"
    }

    fun <T> createAPPAPI(service: Class<T>): T {
        val httpBuilder = OkHttpClient.Builder()
            .connectTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .writeTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .readTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))

        httpBuilder.addInterceptor(HeaderInterceptor(false))
        httpBuilder.addInterceptor(TokenFetcherInterceptor())

        return Retrofit.Builder()
            .baseUrl(APP_API_HOST)
            .addConverterFactory(GsonConverterFactory.create())
            .client(httpBuilder.build())
            .build()
            .create(service)
    }

    fun <T> createOAuthAPI(service: Class<T>): T {
        val httpBuilder = OkHttpClient.Builder()
            .connectTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .writeTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .readTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))

        httpBuilder.addInterceptor(HeaderInterceptor(false))

        return Retrofit.Builder()
            .baseUrl(OAUTH_HOST)
            .addConverterFactory(GsonConverterFactory.create())
            .client(httpBuilder.build())
            .build()
            .create(service)
    }
}