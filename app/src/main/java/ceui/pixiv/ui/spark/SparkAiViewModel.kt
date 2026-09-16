package ceui.pixiv.ui.spark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SparkMessage(val user: Boolean, val text: String)
data class SparkUiState(
    val messages: List<SparkMessage> = emptyList(),
    val generating: Boolean = false,
    val translation: String = "",
    val translating: Boolean = false,
    val error: String? = null,
)

class SparkAiViewModel : ViewModel() {
    // A stalled SSE connection must eventually surface as an error. Infinite
    // read timeout leaves the UI stuck on “Translating…” forever when the
    // upstream or proxy stops sending events.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val _state = MutableStateFlow(SparkUiState())
    val state: StateFlow<SparkUiState> = _state.asStateFlow()
    private var chatSource: EventSource? = null
    private var translationSource: EventSource? = null

    fun sendChat(text: String) {
        if (text.isBlank() || _state.value.generating) return
        if (BuildConfig.CODEX_SPARK_API_KEY.isBlank()) { _state.value = _state.value.copy(error = "missing_key"); return }
        val history = _state.value.messages + SparkMessage(true, text)
        _state.value = _state.value.copy(messages = history + SparkMessage(false, ""), generating = true, error = null)
        val body = JSONObject().apply {
            put("model", "gpt-5.3-codex-spark"); put("stream", true)
            put("messages", org.json.JSONArray().apply { history.forEach { put(JSONObject().put("role", if (it.user) "user" else "assistant").put("content", it.text)) } })
        }
        stream("/chat/completions", body, { delta -> updateChat(delta) }, { chatSource = it }, {
            chatSource = null; _state.value = _state.value.copy(generating = false)
        }, { chatSource = null; _state.value = _state.value.copy(generating = false, error = "network") })
    }

    fun stopChat() { chatSource?.cancel(); chatSource = null; _state.value = _state.value.copy(generating = false) }

    fun sendTranslation(text: String, target: String, prompt: String) {
        if (text.isBlank() || _state.value.translating) return
        if (BuildConfig.CODEX_SPARK_API_KEY.isBlank()) { _state.value = _state.value.copy(error = "missing_key"); return }
        _state.value = _state.value.copy(translation = "", translating = true, error = null)
        val body = JSONObject().apply { put("text", text); put("source", "auto"); put("target", target.ifBlank { "中文" }); put("prompt", prompt); put("stream", true) }
        stream("/translate", body, { delta -> _state.value = _state.value.copy(translation = _state.value.translation + delta) }, { translationSource = it }, {
            translationSource = null; _state.value = _state.value.copy(translating = false)
        }, { translationSource = null; _state.value = _state.value.copy(translating = false, error = "network") })
    }

    fun clearChat() { stopChat(); _state.value = _state.value.copy(messages = emptyList(), error = null) }
    fun clearTranslation() { translationSource?.cancel(); translationSource = null; _state.value = _state.value.copy(translation = "", translating = false, error = null) }
    fun consumeError() { _state.value = _state.value.copy(error = null) }

    private fun updateChat(delta: String) {
        val old = _state.value.messages
        if (old.isEmpty()) return
        val next = old.dropLast(1) + old.last().copy(text = old.last().text + delta)
        _state.value = _state.value.copy(messages = next)
    }

    private fun stream(path: String, body: JSONObject, onDelta: (String) -> Unit, onSource: (EventSource) -> Unit, onDone: () -> Unit, onError: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val request = Request.Builder().url("https://ai.pixshaft.com/v1$path")
                .header("Authorization", "Bearer ${BuildConfig.CODEX_SPARK_API_KEY}")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val eventSource = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
                override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                    if (data == "[DONE]") { es.cancel(); viewModelScope.launch(Dispatchers.Main) { onDone() }; return }
                    try {
                        val delta = JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content", "") ?: ""
                        if (delta.isNotEmpty()) viewModelScope.launch(Dispatchers.Main) { onDelta(delta) }
                    } catch (_: Exception) { }
                }
                override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                    viewModelScope.launch(Dispatchers.Main) { onError() }
                }
            })
            onSource(eventSource)
        }
    }
    override fun onCleared() { chatSource?.cancel(); translationSource?.cancel(); client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
}
