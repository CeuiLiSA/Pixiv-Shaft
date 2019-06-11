package ceui.lisa.http;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Collections;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;

public class Retro {

    private static final String ACCOUNT_BASE_URL = "https://oauth.secure.pixiv.net";
    private static final String API_BASE_URL = "https://app-api.pixiv.net/";




    public static AppApi getAppApi(){
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
                message -> Log.i("RetrofitLog","retrofitBack = "+message));
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
        OkHttpClient okHttpClient = new OkHttpClient
                .Builder()
                .addInterceptor(loggingInterceptor)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                //.dns(HttpDns.get())
                .addInterceptor(chain -> {
                    Request localRequest = chain.request().newBuilder()
                            .addHeader("User-Agent:", "PixivAndroidApp/5.0.134 (Android 6.0.1; D6653)")
                            .addHeader("Accept-Language", "zh_CN")
                            .build();
                    return chain.proceed(localRequest);
                })
                .addInterceptor(new TokenInterceptor())
                .build();
        Gson gson = new GsonBuilder()
                .setLenient()
                //.excludeFieldsWithModifiers(Modifier.FINAL, Modifier.TRANSIENT, Modifier.STATIC)
                .create();
        Retrofit retrofit = new Retrofit.Builder()
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .baseUrl(API_BASE_URL)
                .build();
        return retrofit.create(AppApi.class);
    }

    public static AccountApi getAccountApi(){
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(
                message -> Log.i("RetrofitLog","retrofitBack = "+message));
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
                .baseUrl(ACCOUNT_BASE_URL)
                .build();
        return retrofit.create(AccountApi.class);
    }
}