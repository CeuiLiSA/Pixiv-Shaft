package ceui.loxia

import ceui.lisa.BuildConfig
import ceui.lisa.activities.Shaft
import ceui.lisa.http.CronetInterceptor
import ceui.pixiv.shaftapi.ShaftHmac
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object Client {

    private val clientManager = ClientManager()

    private var _appApi: API? = null

    val appApi: API get() {
        val _api = _appApi
        return if (_api != null) {
            _api
        } else {
            val impl = clientManager.createAPPAPI(API::class.java)
            _appApi = impl
            impl
        }
    }

    fun reset() {
        _appApi = null
        _appApi = clientManager.createAPPAPI(API::class.java)
        // 网页 API 也带直连拦截器,直连开关切换后必须一起重建,否则要重启 App 才生效。
        _webApi = null
    }

    private var _webApi: PixivWebApi? = null

    val webApi: PixivWebApi get() {
        val _api = _webApi
        return if (_api != null) {
            _api
        } else {
            val impl = clientManager.createWebAPIService(PixivWebApi::class.java)
            _webApi = impl
            impl
        }
    }

    val moonAPI: MoonAPI by lazy {
        clientManager.createMoonAPIService(MoonAPI::class.java)
    }

    // pixshaft-api (browse history). Real public domain + Let's Encrypt cert,
    // so plain system DNS/TLS — no custom Dns like moonAPI needs.
    val pixshaft: PixshaftApi by lazy {
        clientManager.createPixshaftService(PixshaftApi::class.java)
    }
}

class ClientManager {

    companion object {
        const val APP_API_HOST = "https://app-api.pixiv.net"
        const val WEB_API_HOST = "https://www.pixiv.net"
        const val NETEASY_API_HOST = "http://192.243.123.124:3000"

        // moonAPI: self-hosted backend (settings sync, etc.)
        // Hostname is virtual; resolved by custom OkHttp Dns to the real IP.
        // To migrate IP / domain, update MOON_BACKEND_IP only.
        const val MOON_API_HOST = "https://shaft.api:8443/"
        const val MOON_BACKEND_HOSTNAME = "shaft.api"
        const val MOON_BACKEND_IP = "111.229.197.181"

        // pixshaft-api: browse-history backend, real public domain.
        const val PIXSHAFT_API_HOST = "https://pixshaft.com/"

        /**
         * 所有 Web API 请求和 WebView 统一使用的 User-Agent。
         * cf_clearance cookie 绑定 UA，WebView 和 OkHttp 必须一致。
         */
        const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.39 Mobile Safari/537.36"

        const val TOKEN_HEAD = "Bearer "

        const val HEADER_AUTH = "authorization"

        const val REQUIEST_TIME = 10L

        const val TOKEN_ERROR_1 = "Error occurred at the OAuth process"
        const val TOKEN_ERROR_2 = "Invalid refresh token"
    }

    private fun applyDirectConnect(builder: OkHttpClient.Builder) {
        if (Shaft.sSettings.isDirectConnect) {
            builder.addInterceptor(CronetInterceptor(CronetInterceptor.getEngine(Shaft.getContext())))
        }
    }

    fun <T> createAPPAPI(service: Class<T>): T {
        val okhttpClientBuilder = OkHttpClient.Builder()
            .connectTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .writeTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .readTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))

        okhttpClientBuilder.addInterceptor(HeaderInterceptor())
        okhttpClientBuilder.addInterceptor(TokenFetcherInterceptor())
        okhttpClientBuilder.addInterceptor(HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
        })
        applyDirectConnect(okhttpClientBuilder)

        return Retrofit.Builder()
            .baseUrl(APP_API_HOST)
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
        // issue #959: www.pixiv.net 也走直连(Cronet QUIC + CF IP),否则网页端专属功能
        // (拉黑、Web 首页、按 tag 筛作品…)在没梯子的网络上一律超时。
        applyDirectConnect(httpBuilder)

        return Retrofit.Builder()
            .baseUrl(WEB_API_HOST)
            .addConverterFactory(GsonConverterFactory.create())
            .client(httpBuilder.build())
            .build()
            .create(service)
    }

    fun <T> createPixshaftService(service: Class<T>): T {
        val httpBuilder = OkHttpClient.Builder()
            // Fail fast when the history backend is down/overloaded so the UI can
            // fall back to the local DB quickly instead of hanging ~10s.
            .connectTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            // Sign ONLY the /v1/account/* endpoints (email-bound backup/restore)
            // with X-Shaft-Sign = HMAC-SHA256(body, SHAFT_EVENTS_HMAC). History is
            // intentionally unsigned. Empty secret (fork builds) → no header.
            .addInterceptor { chain ->
                val req = chain.request()
                val body = req.body
                val secret = BuildConfig.SHAFT_EVENTS_HMAC
                if (body == null || secret.isEmpty() || !req.url.encodedPath.contains("/v1/account/")) {
                    chain.proceed(req)
                } else {
                    val buf = Buffer().also { body.writeTo(it) }
                    val sig = ShaftHmac.signHex(buf.readUtf8(), secret)
                    chain.proceed(req.newBuilder().header("X-Shaft-Sign", sig).build())
                }
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                // BASIC, not BODY: history payloads are large, keep logs sane.
                setLevel(HttpLoggingInterceptor.Level.BASIC)
            })
        applyDirectConnect(httpBuilder)
        return Retrofit.Builder()
            .baseUrl(PIXSHAFT_API_HOST)
            .addConverterFactory(GsonConverterFactory.create())
            .client(httpBuilder.build())
            .build()
            .create(service)
    }

    fun <T> createMoonAPIService(service: Class<T>): T {
        val moonDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return if (hostname == MOON_BACKEND_HOSTNAME) {
                    listOf(InetAddress.getByName(MOON_BACKEND_IP))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        }

        val httpBuilder = OkHttpClient.Builder()
            .connectTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .writeTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .readTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .dns(moonDns)
            // 自建后端在国内,不走系统 HTTP 代理(Clash 等);
            // 注意:这绕不过 Clash 的 TUN/VPN 模式,TUN 用户需要在 Clash 规则里
            // 把 shaft.api / 111.229.197.181 设为 DIRECT。
            .proxy(Proxy.NO_PROXY)
            .addInterceptor(HttpLoggingInterceptor().apply {
                setLevel(HttpLoggingInterceptor.Level.BODY)
            })
        return Retrofit.Builder()
            .baseUrl(MOON_API_HOST)
            .addConverterFactory(GsonConverterFactory.create())
            .client(httpBuilder.build())
            .build()
            .create(service)
    }
}
