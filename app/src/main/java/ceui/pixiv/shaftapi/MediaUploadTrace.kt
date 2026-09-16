package ceui.pixiv.shaftapi

import ceui.lisa.BuildConfig
import java.util.concurrent.atomic.AtomicInteger
import retrofit2.HttpException
import timber.log.Timber

/** Debug-only trace. Only preview_url includes its signed URL for manual preview diagnostics. */
class MediaUploadTrace internal constructor(private val operation: String) {
    private val id = nextId.incrementAndGet()
    private val started = System.nanoTime()
    private var stage = "start"
    private var stageStarted = started

    fun stage(name: String, details: String = "") {
        stage = name
        stageStarted = System.nanoTime()
        event("start", details)
    }

    fun event(event: String, details: String = "") {
        if (!BuildConfig.DEBUG) return
        Timber.tag(TAG).d("%s %s %s", prefix(), event, details)
    }

    fun failure(error: Exception) {
        if (!BuildConfig.DEBUG) return
        val response = (error as? HttpException)?.response()
        Timber.tag(TAG)
            .e(
                "%s failed type=%s http=%s requestId=%s",
                prefix(),
                error.javaClass.simpleName,
                response?.code(),
                response?.headers()?.get("X-Request-Id"),
            )
        // Throwable messages can contain the complete signed URL or local content URI.
        // Keep exception types and source locations without serializing those messages.
        var cause: Throwable? = error
        repeat(4) { depth ->
            val current = cause ?: return
            Timber.tag(TAG)
                .e(
                    "#%d cause[%d]=%s\n%s",
                    id,
                    depth,
                    current.javaClass.name,
                    current.stackTrace.take(12).joinToString("\n") { "  at $it" },
                )
            cause = current.cause
        }
    }

    private fun prefix(): String {
        val now = System.nanoTime()
        return "#$id $operation +${(now - started) / 1_000_000}ms " +
            "stage=$stage stageMs=${(now - stageStarted) / 1_000_000}"
    }

    companion object {
        private const val TAG = "MediaUpload"
        private val nextId = AtomicInteger()
    }
}
