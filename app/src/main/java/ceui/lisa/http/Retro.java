package ceui.lisa.http;

import static ceui.lisa.http.AppApi.API_BASE_URL;
import static ceui.lisa.http.ResourceApi.JSDELIVR_BASE_URL;
import static ceui.lisa.http.SignApi.SIGN_API;

import timber.log.Timber;

import com.blankj.utilcode.util.DeviceUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import ceui.lisa.activities.Shaft;
import ceui.lisa.helper.LanguageHelper;
import ceui.pixiv.session.SessionManager;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class Retro {

    /**
     * {@link AppApi}：app-api 的 suspend 接口，单例 Retrofit 上 create（Retrofit 内部缓存方法解析，
     * 每次 create 很便宜）。
     */
    public static AppApi getAppApi() {
        return get().create(AppApi.class);
    }

    public static void refreshAppApi() {
        Holder.appRetrofit = buildRetrofit(API_BASE_URL);
    }

    public static SignApi getSignApi() {
        return buildRetrofit(SIGN_API).create(SignApi.class);
    }

    public static AccountTokenApi getAccountTokenApi() {
        return buildRetrofit(AccountTokenApi.ACCOUNT_BASE_URL).create(AccountTokenApi.class);
    }

    public static ResourceApi getResourceApi() {
        return buildPlainRetrofit(JSDELIVR_BASE_URL).create(ResourceApi.class);
    }

    private static Request.Builder addHeader(Request.Builder before) {
        PixivHeaders pixivHeaders = new PixivHeaders();
        String osVersion = DeviceUtils.getSDKVersionName();
        String phoneName = DeviceUtils.getModel();
        before.addHeader("User-Agent", "PixivAndroidApp/5.0.234 (Android " + osVersion + "; " + phoneName + ")")
                .addHeader("accept-language", LanguageHelper.getRequestHeaderAcceptLanguageFromAppLanguage())
                .addHeader("x-client-time", pixivHeaders.getXClientTime())
                .addHeader("x-client-hash", pixivHeaders.getXClientHash());
        return before;
    }

    private static OkHttpClient.Builder applyDirectConnect(OkHttpClient.Builder before, boolean enable) {
        if (enable && Shaft.sSettings.isDirectConnect()) {
            before.addInterceptor(new CronetInterceptor(CronetInterceptor.getEngine(Shaft.getContext())));
        }
        return before;
    }

    /**
     * @param baseUrl The base url
     *                <p>
     *                For example:
     *                </p>
     *                <p>
     *                String API_BASE_URL = "https://app-api.pixiv.net/";in {@link AppApi}
     *                </p>
     *
     */
    private static Retrofit buildRetrofit(String baseUrl) {
        return buildRetrofit(baseUrl, true);
    }

    /**
     * Retrofit: A Type-Safe HTTP Client for Android and JVM
     * Retrofit is a popular and powerful type-safe HTTP client library for Android and Java Virtual Machine (JVM) applications. It simplifies the process of making network requests by converting your REST API endpoints into Java interfaces.
     *
     * @param baseUrl       The base URL
     * @param directConnect auto
     *
     */
    private static Retrofit buildRetrofit(String baseUrl, boolean directConnect) {
        OkHttpClient.Builder builder = getLogClient();
        // AppApi / OAuth 刷新 / PxveAPI 代理共用 AppApiTimeouts。
        builder.connectTimeout(AppApiTimeouts.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(AppApiTimeouts.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(AppApiTimeouts.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            builder.addInterceptor(chain -> {
                Request original = chain.request();
                Request.Builder reqBuilder = addHeader(original.newBuilder());
                if (original.header("Authorization") == null) {
                    String bearerToken = SessionManager.INSTANCE.getBearerTokenOrEmpty();
                    if (!bearerToken.isEmpty()) {
                        reqBuilder.addHeader("Authorization", bearerToken);
                    }
                }
                return chain.proceed(reqBuilder.build());
            });
            builder.addInterceptor(new TokenInterceptor());
        } catch (Exception e) {
            Timber.e(e, "buildRetrofit interceptor error");
        }
        // App API 代理（PxveAPI 风格）与直连模式**共存**：代理拦截器挂在
        // CronetInterceptor 之前，只改写 app-api/oauth 请求到代理域名；改写后的域名
        // 不在 Cronet MAP 规则内，走系统 DNS/TLS 解析，其余请求原样放行给直连。
        // 二者同时开启时互不干扰，关闭任意一个也互不影响。
        if (Shaft.sSettings.isUseAppApiProxy()) {
            builder.addInterceptor(new AppApiProxyInterceptor());
        }
        applyDirectConnect(builder, directConnect);
        HttpLoggingInterceptor l = new HttpLoggingInterceptor(
                message -> Timber.i(message));
        l.setLevel(HttpLoggingInterceptor.Level.BODY);
        builder.addInterceptor(l);
        OkHttpClient client = builder.build();
        Gson gson = new GsonBuilder().setLenient().create();
        return new Retrofit.Builder()
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .baseUrl(baseUrl)
                .build();
    }

    private static Retrofit buildPlainRetrofit(String baseUrl) {
        OkHttpClient.Builder builder = getLogClient();
        OkHttpClient client = builder.build();
        return new Retrofit.Builder()
                .client(client)
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * @return The static Retrofit
     * <p>
     * @see Retrofit retrofit2.Retrofit
     * </p>
     */
    private static Retrofit get() {
        return Holder.appRetrofit;
    }

    public static OkHttpClient.Builder getLogClient() {
        // 公共 builder：只放公共 DNS 与协议；各业务链路的超时由各自 Timeouts 类设置。
        // app-api/oauth/www/comic 系统 DNS 只保留 IPv4（官方单 A 记录，IPv6 多为污染）；
        // 其它 host（含 PxveAPI 代理域名）由 IPv4OnlyDns 原样放行。
        return new OkHttpClient.Builder()
                .dns(IPv4OnlyDns.INSTANCE)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1));
    }

    private static class Holder {
        private static Retrofit appRetrofit = buildRetrofit(API_BASE_URL);
    }
}