package ceui.loxia

import ceui.lisa.BuildConfig
import ceui.lisa.activities.Shaft
import ceui.lisa.http.AppApiProxyInterceptor
import ceui.lisa.http.AppApiTimeouts
import ceui.lisa.http.CronetInterceptor
import ceui.lisa.http.IPv4OnlyDns
import ceui.lisa.http.WebApiTimeouts
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
        // comic 同理:它也 applyDirectConnect,漏了这行切完直连开关漫画页还是走旧客户端。
        _comicApi = null
        _fanboxApi = null
    }

    @Volatile
    private var _fanboxApi: FanboxApi? = null

    val fanboxApi: FanboxApi get() {
        val _api = _fanboxApi
        return if (_api != null) {
            _api
        } else {
            val impl = clientManager.createFanboxService(FanboxApi::class.java)
            _fanboxApi = impl
            impl
        }
    }

    @Volatile
    private var _comicApi: ComicApi? = null

    val comicApi: ComicApi get() {
        val _api = _comicApi
        return if (_api != null) {
            _api
        } else {
            val impl = clientManager.createComicService(ComicApi::class.java)
            _comicApi = impl
            impl
        }
    }

    // @Volatile:reset() 可能在设置页线程写,而读者散在各个 UI/IO 协程里 —— 没它切完直连开关
    // 有的线程还会拿到旧客户端。
    @Volatile
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

        // pixiv COMIC。和 app-api 同一套 OAuth token,只是换个 host。
        const val COMIC_API_HOST = "https://comic.pixiv.net/"

        // pixiv FANBOX。cookie 认证,和 OAuth 那套无关,见 FanboxHeaderInterceptor。
        const val FANBOX_API_HOST = "https://api.fanbox.cc/"

        /**
         * 所有 Web API 请求和 WebView 统一使用的 User-Agent。
         * cf_clearance cookie 绑定 UA，WebView 和 OkHttp 必须一致。
         */
        const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.39 Mobile Safari/537.36"

        const val TOKEN_HEAD = "Bearer "

        const val HEADER_AUTH = "authorization"

        // 非 app-api / 非网页 ajax 的 loxia 客户端（comic / fanbox / moon）仍用这个值；
        // app-api 与 www 网页 ajax 已分别收敛到 AppApiTimeouts / WebApiTimeouts。
        const val REQUIEST_TIME = 10L

        const val TOKEN_ERROR_1 = "Error occurred at the OAuth process"
        const val TOKEN_ERROR_2 = "Invalid refresh token"
    }

    /**
     * App API 代理（PxveAPI 风格），只给 [createAPPAPI] 用 —— 其余 client
     * （web / pixshaft / comic / fanbox / moon）根本不发 app-api/oauth 请求，
     * 挂上去只是一个恒放行的空拦截器。与 [Retro.buildRetrofit] 的装配范围保持一致。
     *
     * 必须在 [applyDirectConnect] **之前**调用：改写后的域名（用户自建代理）不在
     * Cronet 的 host_resolver_rules MAP 规则内，交给 Cronet 时走系统 DNS/TLS 解析，
     * 二者共存互不干扰。
     */
    private fun applyAppApiProxy(builder: OkHttpClient.Builder) {
        if (Shaft.sSettings.isUseAppApiProxy) {
            builder.addInterceptor(AppApiProxyInterceptor())
        }
    }

    private fun applyDirectConnect(builder: OkHttpClient.Builder) {
        if (Shaft.sSettings.isDirectConnect) {
            builder.addInterceptor(CronetInterceptor(CronetInterceptor.getEngine(Shaft.getContext())))
        }
    }

    fun <T> createAPPAPI(service: Class<T>): T {
        // app-api 超时收敛到 AppApiTimeouts；
        // PxveAPI 代理开启时，AppApiProxyInterceptor 改写后的请求仍走本 client，同样生效。
        val okhttpClientBuilder = OkHttpClient.Builder()
            .connectTimeout(AppApiTimeouts.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(AppApiTimeouts.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AppApiTimeouts.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .dns(IPv4OnlyDns)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))

        okhttpClientBuilder.addInterceptor(HeaderInterceptor())
        okhttpClientBuilder.addInterceptor(TokenFetcherInterceptor())
        okhttpClientBuilder.addInterceptor(HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
        })
        applyAppApiProxy(okhttpClientBuilder)
        applyDirectConnect(okhttpClientBuilder)

        return Retrofit.Builder()
            .baseUrl(APP_API_HOST)
            .addConverterFactory(GsonConverterFactory.create())
            .client(okhttpClientBuilder.build())
            .build()
            .create(service)
    }

    fun <T> createWebAPIService(service: Class<T>): T {
        // www.pixiv.net 网页 ajax 用独立 WebApiTimeouts，不跟 app-api 混用；
        // 直连（Cronet）路径不改动，仍由 CronetInterceptor 自己处理。
        val httpBuilder = OkHttpClient.Builder()
            .connectTimeout(WebApiTimeouts.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WebApiTimeouts.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(WebApiTimeouts.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .dns(IPv4OnlyDns)
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

    /**
     * pixiv COMIC。除了 baseUrl 之外和 [createAPPAPI] 完全一致 —— 同一套 Bearer、同一套
     * 401/token 刷新逻辑,所以 [HeaderInterceptor] / [TokenFetcherInterceptor] 直接复用。
     * comic.pixiv.net 和 app-api 一样在墙内不可达,必须跟着走直连。
     */
    fun <T> createComicService(service: Class<T>): T {
        // comic.pixiv.net 也没有 IPv6 DNS 记录，同样用 IPv4OnlyDns 滤掉污染的 IPv6。
        val httpBuilder = OkHttpClient.Builder()
            .connectTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .writeTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .readTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .dns(IPv4OnlyDns)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))

        httpBuilder.addInterceptor(HeaderInterceptor())
        httpBuilder.addInterceptor(TokenFetcherInterceptor())
        httpBuilder.addInterceptor(HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BASIC)
        })
        applyDirectConnect(httpBuilder)

        return Retrofit.Builder()
            .baseUrl(COMIC_API_HOST)
            .addConverterFactory(GsonConverterFactory.create())
            .client(httpBuilder.build())
            .build()
            .create(service)
    }

    /**
     * pixiv FANBOX。不挂 [HeaderInterceptor](那套 Bearer 在 FANBOX 无效),只挂
     * [FanboxHeaderInterceptor] 带 Origin + WebView cookie。api.fanbox.cc 在墙内不可达,
     * 跟着走直连。
     */
    fun <T> createFanboxService(service: Class<T>): T {
        val httpBuilder = OkHttpClient.Builder()
            .connectTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .writeTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .readTimeout(REQUIEST_TIME, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))

        httpBuilder.addInterceptor(FanboxHeaderInterceptor())
        httpBuilder.addInterceptor(HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BASIC)
        })
        applyDirectConnect(httpBuilder)

        return Retrofit.Builder()
            .baseUrl(FANBOX_API_HOST)
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
