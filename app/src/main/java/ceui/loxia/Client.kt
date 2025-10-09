package ceui.loxia

import ceui.lisa.http.AccountTokenApi
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object Client {

    private val clientManager = ClientManager()

    private var _appApi: API? = null

    val appApi: API
        get() {
            val _api = _appApi
            return if (_api != null) {
                _api
            } else {
                val impl = clientManager.createAPPAPI(API::class.java)
                _appApi = impl
                impl
            }
        }

    val shaftClient: OkHttpClient
        get() {
            return clientManager.shaftClient
        }

    fun reset() {
        _appApi = null
        _appApi = clientManager.createAPPAPI(API::class.java)
    }

    val authApi: AccountTokenApi by lazy {
        clientManager.createOAuthAPI(AccountTokenApi::class.java)
    }

    val webApi: PixivWebApi by lazy {
        clientManager.createWebAPIService(PixivWebApi::class.java)
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

        const val REQUIEST_TIME = 5L

        const val TOKEN_ERROR_1 = "Error occurred at the OAuth process"
        const val TOKEN_ERROR_2 = "Invalid refresh token"
    }

    private var _shaftClient: OkHttpClient? = null
    val shaftClient: OkHttpClient
        get() {
            val theClient = _shaftClient
            if (theClient != null) {
                return theClient
            }

            return appClient().also {
                _shaftClient = it
            }
        }

    private fun appClient(): OkHttpClient {
        val okhttpClientBuilder = OkHttpClient.Builder()
            .connectTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .writeTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .readTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))

        okhttpClientBuilder.addInterceptor(HeaderInterceptor(true))
        okhttpClientBuilder.addInterceptor(TokenFetcherInterceptor())
        okhttpClientBuilder.addInterceptor(HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
        })

        return okhttpClientBuilder.build()
    }

    fun <T> createAPPAPI(service: Class<T>): T {
        return Retrofit.Builder()
            .baseUrl(APP_API_HOST)
            .addConverterFactory(GsonConverterFactory.create())
            .client(shaftClient)
            .build()
            .create(service)
    }

    fun <T> createOAuthAPI(service: Class<T>): T {
        val okhttpClientBuilder = OkHttpClient.Builder()
            .connectTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .writeTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .readTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))

        okhttpClientBuilder.addInterceptor(HeaderInterceptor(false))
        okhttpClientBuilder.addInterceptor(HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
        })
        okhttpClientBuilder.addInterceptor(HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
        })

        return Retrofit.Builder()
            .baseUrl(OAUTH_HOST)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okhttpClientBuilder.build())
            .build()
            .create(service)
    }

    fun <T> createWebAPIService(service: Class<T>): T {
        val httpBuilder = OkHttpClient.Builder()
            .connectTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .writeTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .readTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))

        httpBuilder.addInterceptor(WebHeaderInterceptor())
        httpBuilder.addInterceptor(HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
        })
        return Retrofit.Builder()
            .baseUrl(WEB_API_HOST)
            .addConverterFactory(GsonConverterFactory.create())
            .client(httpBuilder.build())
            .build()
            .create(service)
    }
}