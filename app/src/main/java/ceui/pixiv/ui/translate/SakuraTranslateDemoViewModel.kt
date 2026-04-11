package ceui.pixiv.ui.translate

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.loxia.asLiveData
import kotlinx.coroutines.launch

class SakuraTranslateDemoViewModel : ViewModel() {

    data class Meta(val total: Int, val failed: Int, val elapsedMs: Long)

    private val _isTranslating = MutableLiveData(false)
    val isTranslating: LiveData<Boolean> get() = _isTranslating.asLiveData()

    /** (done, total) while running, null before any batch starts / while model loads */
    private val _progress = MutableLiveData<Pair<Int, Int>?>(null)
    val progress: LiveData<Pair<Int, Int>?> get() = _progress.asLiveData()

    private val _output = MutableLiveData<String>("")
    val output: LiveData<String> get() = _output.asLiveData()

    private val _meta = MutableLiveData<Meta?>(null)
    val meta: LiveData<Meta?> get() = _meta.asLiveData()

    /**
     * Start a translation batch. Caller is responsible for trimming/splitting input.
     *
     * @return false if Sakura model is not downloaded yet — caller should trigger download.
     *         true if the batch was enqueued (already-running case is also true: we just no-op).
     */
    fun translate(
        context: Context,
        lines: List<String>,
        glossary: String?
    ): Boolean {
        if (_isTranslating.value == true) return true

        val model = SakuraModel.SAKURA_1_5B
        if (!SakuraModelManager.isModelReady(context, model)) {
            return false
        }

        _isTranslating.value = true
        _progress.value = null
        _output.value = ""
        _meta.value = null

        val startMs = System.currentTimeMillis()
        val appContext = context.applicationContext

        viewModelScope.launch {
            val results = SakuraTranslator.translateBatch(
                context = appContext,
                texts = lines,
                glossary = glossary?.ifEmpty { null },
                onProgress = { done, total ->
                    _progress.postValue(done to total)
                }
            )

            val elapsedMs = System.currentTimeMillis() - startMs
            val rendered = results.joinToString("\n") { it ?: "⟨翻译失败⟩" }
            val failed = results.count { it == null }

            _output.postValue(rendered)
            _meta.postValue(Meta(lines.size, failed, elapsedMs))
            _isTranslating.postValue(false)
        }
        return true
    }
}
