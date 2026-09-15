package ceui.pixiv.shaftapi

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Process-wide pools. Auth and media metadata share TLS connections, never interceptors. */
internal object MediaHttpTransport {
    val apiClient: OkHttpClient =
        OkHttpClient.Builder().eventListenerFactory(MediaNetworkTiming.factory).build()
    val storageClient: OkHttpClient =
        OkHttpClient.Builder().eventListenerFactory(MediaNetworkTiming.factory).build()
    private val warmingApi = AtomicBoolean()
    private val warmingStorage = AtomicBoolean()

    /** Hide cold DNS/TCP/TLS setup behind the picker. Never wait for this in upload(). */
    fun prewarm() {
        warm(apiClient, "https://api.pixshaft.com/health", warmingApi)
        warm(storageClient, "https://media.pixshaft.com/", warmingStorage)
    }

    internal fun warm(client: OkHttpClient, url: String, running: AtomicBoolean) {
        if (client.connectionPool.connectionCount() > 0 || !running.compareAndSet(false, true))
            return
        val trace = MediaUploadTrace("prewarm")
        val request =
            Request.Builder().url(url).head().tag(MediaUploadTrace::class.java, trace).build()
        trace.stage("connection", "host=${request.url.host}")
        client
            .newBuilder()
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        running.set(false)
                        trace.event("unavailable", "type=${e.javaClass.simpleName}")
                    }

                    override fun onResponse(call: Call, response: Response) {
                        // A private COS root may answer 403/404; its HTTPS connection is still
                        // reusable.
                        response.use { trace.event("ready", "http=${it.code}") }
                        running.set(false)
                    }
                }
            )
    }
}
