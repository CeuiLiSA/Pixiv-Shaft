package ceui.lisa.http;

import static ceui.lisa.http.AppApi.API_BASE_URL;
import static ceui.lisa.http.ResourceApi.JSDELIVR_BASE_URL;
import static ceui.lisa.http.SignApi.SIGN_API;

import timber.log.Timber;

import com.blankj.utilcode.util.DeviceUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Collections;

import ceui.lisa.activities.Shaft;
import ceui.lisa.helper.LanguageHelper;
import ceui.pixiv.session.SessionManager;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;

public class Retro {

    /**
     * @return AppApi the api that the request needed
     * <p>
     * Configued in {@link AppApi}
     * </p>
     * <p>
     * get() returns a Java interface to HTTP calls,and then it .create(),creates the AppApi
     * </p>
     */
    public static AppApi getAppApi() {
        return get().create(AppApi.class);
    }

    public static LofterApi getLofterApi() {
        return get().create(LofterApi.class);
    }

    /**
     * issue #569: www.pixiv.net 网页 ajax 的 RxJava 客户端。带 {@link ceui.loxia.WebHeaderInterceptor}
     * 注入已同步的网页 cookie(没同步则匿名,公开作品仍可拿)。Retrofit/OkHttpClient 缓存为单例,
     * 翻页时复用连接池(cookie 在拦截器里按请求实时读取,单例不影响其新鲜度)。
     */
    public static WebApi getWebApi() {
        return WebHolder.webRetrofit.create(WebApi.class);
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
        applyDirectConnect(builder, directConnect);
        HttpLoggingInterceptor l = new HttpLoggingInterceptor(
                message -> Timber.i(message));
        l.setLevel(HttpLoggingInterceptor.Level.BODY);
        builder.addInterceptor(l);
        OkHttpClient client = builder.build();
        Gson gson = new GsonBuilder().setLenient().create();
        return new Retrofit.Builder()
                .client(client)
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * issue #569: 网页 ajax 专用 Retrofit —— www.pixiv.net + cookie 注入 + RxJava 适配器。
     * 不挂 app-api 的 Authorization/x-client-hash 那套(网页接口认 cookie 不认 OAuth)。
     */
    private static Retrofit buildWebRetrofit() {
        OkHttpClient.Builder builder = getLogClient();
        builder.addInterceptor(new ceui.loxia.WebHeaderInterceptor());
        applyDirectConnect(builder, true);
        HttpLoggingInterceptor l = new HttpLoggingInterceptor(message -> Timber.i(message));
        l.setLevel(HttpLoggingInterceptor.Level.BODY);
        builder.addInterceptor(l);
        OkHttpClient client = builder.build();
        Gson gson = new GsonBuilder().setLenient().create();
        return new Retrofit.Builder()
                .client(client)
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .baseUrl(ceui.loxia.ClientManager.WEB_API_HOST + "/")
                .build();
    }

    private static class WebHolder {
        private static final Retrofit webRetrofit = buildWebRetrofit();
    }

    private static Retrofit buildPlainRetrofit(String baseUrl) {
        OkHttpClient.Builder builder = getLogClient();
        OkHttpClient client = builder.build();
        return new Retrofit.Builder()
                .client(client)
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
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
        return new OkHttpClient.Builder()
                .protocols(Collections.singletonList(Protocol.HTTP_1_1));
    }

    private static class Holder {
        private static Retrofit appRetrofit = buildRetrofit(API_BASE_URL);
    }
}