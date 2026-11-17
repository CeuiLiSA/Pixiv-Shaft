package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.pixiv.ui.translate.AiTranslator
import ceui.pixiv.ui.translate.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException

/** 列表标题译文只用于展示，不改 Novel.title，避免污染详情、导出与 feed 缓存（#1372）。 */
class NovelTitleTranslationViewModel : ViewModel() {
    data class State(val running: Boolean = false, val error: Exception? = null)

    private val _state = MutableLiveData(State())
    val state: LiveData<State> = _state
    private val translations = mutableMapOf<String, String>()
    private var targetLang: String? = null
    private var translator: Translator? = null
    private var job: Job? = null
    var shown: Boolean = false
        private set

    fun title(original: String?, lang: String): String =
        if (shown && lang == targetLang) translations[original] ?: original.orEmpty() else original.orEmpty()

    fun hide() {
        job?.cancel()
        shown = false
        _state.value = State()
    }

    fun translate(titles: List<String>, lang: String, engine: Translator) {
        if (_state.value?.running == true) return
        if (targetLang != lang || translator !== engine) {
            translations.clear()
            targetLang = lang
            translator = engine
        }
        shown = true
        val pending = titles.filter { it.isNotBlank() && it !in translations }.distinct()
        _state.value = State(running = pending.isNotEmpty())
        if (pending.isEmpty()) return
        job = viewModelScope.launch {
            try {
                // 小批完成即上屏，失败只补缺项；重复标题只请求一次。
                for ((from, to) in AiTranslator.chunkByCharLimit(pending, 3000)) {
                    val slice = pending.subList(from, to)
                    val translated = engine.translateBatch(slice, lang)
                    ensureActive()
                    if (translated.size != slice.size) throw IOException("Novel title count mismatch")
                    slice.indices.forEach { index ->
                        translated[index].takeIf { it.isNotBlank() }?.let { translations[slice[index]] = it }
                    }
                    _state.value = State(running = true)
                    if (translated.any { it.isBlank() }) throw IOException("Novel title translation incomplete")
                }
                _state.value = State()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State(error = e)
            }
        }
    }

    /** 失败提示按发请求时的引擎分流，不能受后台修改设置影响。 */
    fun translationEngine(): Translator? = translator
}
