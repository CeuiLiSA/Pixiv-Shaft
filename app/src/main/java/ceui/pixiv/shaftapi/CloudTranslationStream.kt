package ceui.pixiv.shaftapi

import ceui.lisa.BuildConfig
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import kotlin.coroutines.resume

/** Reads on OkHttp's worker; cancellation closes the call even while waiting for the next SSE line. */
internal suspend fun PixshaftApi.translateTextsStreaming(
    uid: Long,
    texts: List<String>,
    lang: String,
    onThinking: (String) -> Unit,
    onGenerating: () -> Unit,
): TranslateResult {
    if (uid <= 0) return TranslateResult.InvalidResponse()
    if (texts.isEmpty()) return TranslateResult.Success(emptyList(), emptyList(), System.currentTimeMillis())
    return suspendCancellableCoroutine { continuation ->
        val trace = TranslationStreamTrace()
        trace.event("request", "items=${texts.size} lang=$lang")
        val call = translateStreamRaw(TranslateRequest(uid, texts, lang), if (BuildConfig.DEBUG) "1" else null)
        continuation.invokeOnCancellation { trace.event("cancel", ""); call.cancel() }
        call.enqueue(object : Callback<ResponseBody> {
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                trace.event("failure", t.javaClass.simpleName)
                if (continuation.isActive) continuation.resume(TranslateResult.NetworkFailure(t as? IOException ?: IOException(t)))
            }

            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                trace.event("response", "HTTP ${response.code()} ${response.headers()["Content-Type"]}")
                val result = try {
                    if (!response.isSuccessful) {
                        response.errorBody()?.use {
                            decodeTranslationResponse(Response.error(it, response.raw()), texts.size)
                        } ?: TranslateResult.HttpFailure(response.code())
                    } else response.body()?.use { body ->
                        if (body.contentType()?.subtype != "event-stream") {
                            if (continuation.isActive) onGenerating()
                            // Server rollout/rollback can still answer JSON; never replay a billable request.
                            decodeTranslationResponse(Response.success(Gson().fromJson(body.charStream(), TranslateResponse::class.java)), texts.size)
                        } else {
                            readTranslationEvents(body, texts.size, trace,
                                onThinking = { if (continuation.isActive) onThinking(it) },
                                onGenerating = { if (continuation.isActive) onGenerating() })
                        }
                    } ?: TranslateResult.InvalidResponse()
                } catch (e: IOException) {
                    TranslateResult.NetworkFailure(e)
                } catch (e: Exception) {
                    TranslateResult.InvalidResponse(e)
                }
                trace.event("complete", result.javaClass.simpleName)
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }
}

private fun readTranslationEvents(
    body: ResponseBody,
    expected: Int,
    trace: TranslationStreamTrace,
    onThinking: (String) -> Unit,
    onGenerating: () -> Unit,
): TranslateResult {
    val source = body.source()
    var event = ""
    val data = StringBuilder()
    var generating = false
    while (!source.exhausted()) {
        val line = source.readUtf8LineStrict(1_000_000)
        if (line.isEmpty()) {
            if (data.isNotEmpty()) {
                val json = JsonParser.parseString(data.toString()).asJsonObject
                trace.event(event, json.toString())
                when (event) {
                    "phase" -> when (json.get("phase")?.asString) {
                        "thinking" -> if (!generating) {
                            json.get("reasoning_content")?.takeUnless { it.isJsonNull }?.asString
                                ?.takeLast(240)?.dropWhile { Character.isLowSurrogate(it) }
                                ?.takeIf { it.isNotBlank() }?.let(onThinking)
                        }
                        "generating" -> if (!generating) { generating = true; onGenerating() }
                    }
                    "result" -> return decodeTranslationResponse(
                        Response.success(Gson().fromJson(json, TranslateResponse::class.java)), expected)
                    "error" -> return TranslateResult.HttpFailure(json.get("status")?.asInt ?: 502, json.get("error")?.asString)
                }
            }
            event = ""
            data.setLength(0)
        } else if (line.startsWith("event:")) {
            event = line.substring(6).trim()
        } else if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.substring(5).removePrefix(" "))
            if (data.length > 1_000_000) throw IOException("Translation event too large")
        }
    }
    // Partial content or a heartbeat does not count as a successfully billed translation.
    throw IOException("Translation stream ended without a result")
}

/** Debug APK only. A request id separates concurrent pages; elapsed time starts before HTTP. */
private class TranslationStreamTrace {
    private val id = nextId.incrementAndGet()
    private val started = System.nanoTime()
    private var remaining = 32_000

    @Synchronized fun event(event: String, payload: String) {
        if (!BuildConfig.DEBUG || remaining <= 0) return
        val ms = (System.nanoTime() - started) / 1_000_000
        val clipped = payload.take(remaining)
        remaining -= clipped.length
        val parts = clipped.chunked(1000).ifEmpty { listOf("") }
        parts.forEachIndexed { index, part ->
            Timber.tag("CloudTranslateStream").d("#%d +%dms %s [%d/%d] %s", id, ms, event, index + 1, parts.size, part)
        }
        if (clipped.length < payload.length) Timber.tag("CloudTranslateStream").d("#%d trace text limit reached", id)
    }

    companion object { private val nextId = AtomicInteger() }
}
