package ceui.pixiv.chat.api

import ceui.pixiv.auth.ShaftSourceAuthorization
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit client for [ShaftChatApi]. Separate from [ShaftEventsClient][ceui.pixiv.events.ShaftEventsClient]
 * so chat's read-tier timeouts don't share fate with the events batch
 * write tier (which is more tolerant of slow responses).
 */
object ShaftChatHttpClient {

    val api: ShaftChatApi by lazy { build() }

    private fun build(): ShaftChatApi {
        val builder = ShaftSourceAuthorization.attachTo(OkHttpClient.Builder()) { path ->
            path == "/api/v1/chat/profile" || path == "/api/v1/chat/conversations" ||
                (path.startsWith("/api/v1/chat/conversations/") && path.endsWith("/read"))
        }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))

        return Retrofit.Builder()
            .baseUrl(ShaftSourceAuthorization.baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .client(builder.build())
            .build()
            .create(ShaftChatApi::class.java)
    }
}
