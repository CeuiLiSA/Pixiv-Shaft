package ceui.pixiv.events

import ceui.lisa.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * shaft-api-v2 — community trending events.
 * Backend lives in https://github.com/SoxiaLiSA/shaft-api-v2
 *
 * Deliberately NOT routed through ceui.loxia.Client: pixiv auth headers,
 * token-refresh logic and Cronet are irrelevant here, and a separate
 * OkHttp instance keeps short event timeouts from interfering with the
 * 10s timeouts used for pixiv calls.
 */
interface ShaftEventsApi {

    /** Body must be the exact bytes whose HMAC was computed; do NOT use @Body with Gson. */
    @POST("/api/v1/events/batch")
    suspend fun batch(
        @Header("X-Shaft-Sign") sig: String,
        @Body body: RequestBody
    ): EventBatchResponse
}

data class EventBatchResponse(
    val accepted: Int,
    val deduped: Int,
    val total: Int
)

object ShaftEventsClient {

    val api: ShaftEventsApi by lazy { build() }

    private fun build(): ShaftEventsApi {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // HTTP/2 is fine over cleartext for this server (no TLS) since the
            // network_security_config base policy already permits cleartext.
            .protocols(listOf(Protocol.HTTP_1_1))

        if (BuildConfig.IS_DEBUG_MODE) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                // BODY would dump every event; HEADERS is enough to confirm the
                // request was sent and the X-Shaft-Sign / response status.
                level = HttpLoggingInterceptor.Level.HEADERS
            })
        }

        return Retrofit.Builder()
            .baseUrl(BuildConfig.SHAFT_EVENTS_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(builder.build())
            .build()
            .create(ShaftEventsApi::class.java)
    }
}
