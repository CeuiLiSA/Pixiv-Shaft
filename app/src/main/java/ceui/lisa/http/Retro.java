package ceui.lisa.http;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Collections;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;

import ceui.lisa.activities.Shaft;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;

public class Retro {

    //用作登录，刷新token
    private static final String ACCOUNT_BASE_URL = "https://oauth.secure.pixiv.net/";

    //用作各个页面请求数据
    private static final String API_BASE_URL = "https://app-api.pixiv.net/";

    //用作获取会员token
    //private static final String RANK_TOKEN_BASE_URL = "http://jh.aragaki.fun/";
    private static final String RANK_TOKEN_BASE_URL = "http://202.182.113.0";

    //用作注册账号
    private static final String SIGN_API = "https://accounts.pixiv.net/";

    //腾讯统计API
    public static final String TENCENT_API = "https://openapi.mta.qq.com/";



    public static AppApi getAppApi() {
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
                message -> Log.i("RetrofitLog", "retrofitBack = " + message));
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        try {
            builder.addInterceptor(loggingInterceptor)
                    .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                    .addInterceptor(chain -> {
                        Request localRequest = chain.request().newBuilder()
                                .addHeader("User-Agent:", "PixivAndroidApp/5.0.134 (Android 6.0.1; D6653)")
                                .addHeader("Accept-Language", "zh_CN")
                                .build();
                        return chain.proceed(localRequest);
                    })
                    .addInterceptor(new TokenInterceptor())
//                    .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(8088)))
//                    .sslSocketFactory(SSLSocketClient.getSSLSocketFactory())
//                    .hostnameVerifier(new HostnameVerifier() {
//                        @Override
//                        public boolean verify(String hostname, SSLSession session) {
//                            return true;
//                        }
//                    })
                    .build();
        }catch (Exception e){
            e.printStackTrace();
        }

//        if(Shaft.sSettings.isAutoFuckChina()){
//            builder.dns(new FuckChinaDns());
//        }
        OkHttpClient client = builder.build();
        Gson gson = new GsonBuilder()
                .setLenient()
                //.excludeFieldsWithModifiers(Modifier.FINAL, Modifier.TRANSIENT, Modifier.STATIC)
                .create();
        Retrofit retrofit = new Retrofit.Builder()
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .baseUrl(API_BASE_URL)
                .build();
        return retrofit.create(AppApi.class);
    }


    public static SignApi getSignApi() {
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
                message -> Log.i("RetrofitLog", "retrofitBack = " + message));
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        OkHttpClient okHttpClient = new OkHttpClient
                .Builder()
                .addInterceptor(loggingInterceptor)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                //.dns(HttpDns.get())
                .addInterceptor(chain -> {
                    Request localRequest = chain.request().newBuilder()
                            .addHeader("User-Agent", "PixivAndroidApp/5.0.144 (Android 6.0.1; D6653)")
                            .addHeader("Accept-Language", "zh_CN")
                            .addHeader("Accept-Encoding", "gzip")
                            .build();
                    return chain.proceed(localRequest);
                })
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(new Gson()))
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .baseUrl(SIGN_API)
                .build();
        return retrofit.create(SignApi.class);
    }

    public static AccountApi getAccountApi() {
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
                message -> Log.i("RetrofitLog", "retrofitBack = " + message));
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
//        OkHttpClient okHttpClient = new OkHttpClient
//                .Builder()
//                .addInterceptor(loggingInterceptor)
//                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
//                .addInterceptor(chain -> {
//                    Request localRequest = chain.request().newBuilder()
//                            .addHeader("User-Agent:", "PixivAndroidApp/5.0.134 (Android 6.0.1; D6653)")
//                            //.addHeader("Accept-Language:", "zh_CN")
//                            .build();
//                    return chain.proceed(localRequest);
//                })
//                .dns(new FuckChinaDns())
//                .build();
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.addInterceptor(loggingInterceptor)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .addInterceptor(chain -> {
                    Request localRequest = chain.request().newBuilder()
                            .addHeader("User-Agent:", "PixivAndroidApp/5.0.134 (Android 6.0.1; D6653)")
                            .addHeader("Accept-Language:", "zh_CN")
                            .build();
                    return chain.proceed(localRequest);
                })
//                .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(8088)))
//                .sslSocketFactory(SSLSocketClient.getSSLSocketFactory())
//                .hostnameVerifier(new HostnameVerifier() {
//                    @Override
//                    public boolean verify(String hostname, SSLSession session) {
//                        return true;
//                    }
//                })
                .build();
//        if(Shaft.sSettings.isAutoFuckChina()){
//            builder.dns(new FuckChinaDns());
//        }
        OkHttpClient client = builder.build();
        Gson gson = new GsonBuilder().setLenient().create();
        Retrofit retrofit = new Retrofit.Builder()
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .baseUrl(ACCOUNT_BASE_URL)
                .build();
        return retrofit.create(AccountApi.class);
    }


    public static RankTokenApi getRankApi() {
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
                message -> Log.i("RetrofitLog", "retrofitBack = " + message));
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        OkHttpClient okHttpClient = new OkHttpClient
                .Builder()
                .addInterceptor(loggingInterceptor)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .addInterceptor(chain -> {
                    Request localRequest = chain.request().newBuilder()
                            .addHeader("User-Agent:", "PixivAndroidApp/5.0.134 (Android 6.0.1; D6653)")
                            //.addHeader("Accept-Language:", "zh_CN")
                            .build();
                    return chain.proceed(localRequest);
                })
                .build();
        Gson gson = new GsonBuilder().setLenient().create();
        Retrofit retrofit = new Retrofit.Builder()
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .baseUrl(RANK_TOKEN_BASE_URL)
                .build();
        return retrofit.create(RankTokenApi.class);
    }

    public static <T> T create(String baseUrl,final Class<T> service) {
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
                message -> Log.i("RetrofitLog", "retrofitBack = " + message));
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        OkHttpClient okHttpClient = new OkHttpClient
                .Builder()
                .addInterceptor(loggingInterceptor)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .addInterceptor(chain -> {
                    Request localRequest = chain.request().newBuilder()
                            .addHeader("User-Agent:", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/75.0.3770.80 Safari/537.36")
                            .addHeader("Accept-Encoding:","gzip, deflate")
                            .addHeader("Accept:","text/html")
                            .build();
                    return chain.proceed(localRequest);
                })
                .build();
        Gson gson = new GsonBuilder().setLenient().create();
        Retrofit retrofit = new Retrofit.Builder()
                .client(okHttpClient)
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .baseUrl(baseUrl)
                .build();
        return retrofit.create(service);
    }





}