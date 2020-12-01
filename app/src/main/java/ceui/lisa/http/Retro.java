package ceui.lisa.http;

import android.util.Log;

import com.blankj.utilcode.util.DeviceUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.safframework.http.interceptor.LoggingInterceptor;

import java.security.cert.X509Certificate;
import java.util.Collections;

import javax.net.ssl.X509TrustManager;

import ceui.lisa.activities.Shaft;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;

import static ceui.lisa.http.AccountApi.ACCOUNT_BASE_URL;
import static ceui.lisa.http.AppApi.API_BASE_URL;
import static ceui.lisa.http.SignApi.SIGN_API;

public class Retro {

    public static AppApi getAppApi() {
        return get().create(AppApi.class);
    }

    public static SignApi getSignApi() {
        return buildRetrofit(SIGN_API).create(SignApi.class);
    }

    public static AccountApi getAccountApi() {
        return buildRetrofit(ACCOUNT_BASE_URL).create(AccountApi.class);
    }

    private static Request.Builder addHeader(Request.Builder before) {
        PixivHeaders pixivHeaders = new PixivHeaders();
        String osVersion = DeviceUtils.getSDKVersionName();
        String phoneName = DeviceUtils.getModel();
        before.addHeader("User-Agent", "PixivAndroidApp/5.0.175 (Android " + osVersion + "; " + phoneName + ")")
                .addHeader("accept-language", "zh-cn")
                .addHeader(":authority", "app-api.pixiv.net")
                .addHeader("x-client-time", pixivHeaders.getXClientTime())
                .addHeader("x-client-hash", pixivHeaders.getXClientHash());

        return before;
    }

    private static Retrofit buildRetrofit(String baseUrl) {
        OkHttpClient.Builder builder = getLogClient();
        try {
            builder.addInterceptor(chain ->
                    chain.proceed(addHeader(chain.request().newBuilder()).build()));
            if (!baseUrl.equals(ACCOUNT_BASE_URL)) {
                builder.addInterceptor(new TokenInterceptor());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (Shaft.sSettings.isAutoFuckChina()) {
            builder.sslSocketFactory(new RubySSLSocketFactory(), new pixivOkHttpClient());
            //builder.dns(new CloudFlareDns(CloudFlareDNSService.Companion.invoke()));
            builder.dns(HttpDns.getInstance());
        }
        OkHttpClient client = builder.build();
        Gson gson = new GsonBuilder().setLenient().create();
        return new Retrofit.Builder()
                .client(client)
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .baseUrl(baseUrl)
                .build();
    }

    public static <T> T create(String baseUrl, final Class<T> service) {
        Gson gson = new GsonBuilder().setLenient().create();
        Retrofit retrofit = new Retrofit.Builder()
                .client(
                        getLogClient().addInterceptor(chain -> {
                            Request localRequest = chain.request().newBuilder()
                                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/75.0.3770.80 Safari/537.36")
                                    .addHeader("Accept-Encoding:", "gzip, deflate")
                                    .addHeader("Accept:", "text/html")
                                    .build();
                            return chain.proceed(localRequest);
                        }).build()
                )
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .baseUrl(baseUrl)
                .build();
        return retrofit.create(service);
    }

    static class pixivOkHttpClient implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] param1ArrayOfX509Certificate, String param1String) {
        }

        public void checkServerTrusted(X509Certificate[] param1ArrayOfX509Certificate, String param1String) {
        }

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }


    private static class Holder {
        private static Retrofit appRetrofit = buildRetrofit(API_BASE_URL);
    }

    private static Retrofit get() {
        return Holder.appRetrofit;
    }

    public static OkHttpClient.Builder getLogClient() {
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
                message -> Log.i("RetroLog", message));
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        return new OkHttpClient
                .Builder()
                .addInterceptor(
//                        new LoggingInterceptor.Builder()
//                                .loggable(true)
//                                .request()
//                                .requestTag("Request")
//                                .response()
//                                .responseTag("Response")
//                                .build()
                        loggingInterceptor
                )
                .protocols(Collections.singletonList(Protocol.HTTP_1_1));
    }
}