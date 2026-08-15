package ceui.pixiv.chat.api

import ceui.lisa.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Retrofit client for [ShaftChatApi]. Separate from [ShaftEventsClient][ceui.pixiv.events.ShaftEventsClient]
 * so chat's read-tier timeouts don't share fate with the events batch
 * write tier (which is more tolerant of slow responses).
 */
object ShaftChatHttpClient {

    val api: ShaftChatApi by lazy { build() }

    private fun build(): ShaftChatApi {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))

        if (BuildConfig.IS_DEBUG_MODE) {
            builder.addInterceptor(
                HttpLoggingInterceptor { Timber.tag("Chat-Http").i(it) }
                    .apply { level = HttpLoggingInterceptor.Level.BODY }
            )
        }

        return Retrofit.Builder()
            .baseUrl(BuildConfig.SHAFT_EVENTS_BASE_URL.trimEnd('/') + "/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(builder.build())
            .build()
            .create(ShaftChatApi::class.java)
    }
}
