package ceui.lisa.network

import ceui.lisa.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Legacy non-plaza endpoints. Plaza uses the Tokyo Bearer API. */
object ShaftApiV2Client {

    // Same server as ShaftEventsClient — read endpoints (trending / health) ride
    // the SHAFT_EVENTS_BASE_URL gradle property so custom builds pointing at a
    // private server work without two separate overrides.
    @JvmField val BASE_URL: String = run {
        val raw = BuildConfig.SHAFT_EVENTS_BASE_URL
        if (raw.endsWith('/')) raw else "$raw/"
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val service: ShaftApiV2 = retrofit.create(ShaftApiV2::class.java)

}
