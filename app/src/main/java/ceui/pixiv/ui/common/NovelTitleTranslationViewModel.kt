package ceui.pixiv.ui.common

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.pixiv.ui.translate.AiTranslator
import ceui.pixiv.ui.translate.CloudTranslator
import ceui.pixiv.ui.translate.Translator
import ceui.pixiv.utils.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException

/** 小说译文的缓存身份：AI 是单例，接口、模型或提示词变化也必须使旧译文失效。 */
internal data class NovelTranslationKey(
    val translator: Translator,
    val targetLang: String,
    private val baseUrl: String = "",
    private val model: String = "",
    private val prompt: String = "",
    private val thinkingMode: Int = 0,
) {
    companion object {
        fun capture(translator: Translator, targetLang: String): NovelTranslationKey {
            if (translator !== AiTranslator) return NovelTranslationKey(translator, targetLang)
            val settings = Shaft.sSettings
            return NovelTranslationKey(
                translator, targetLang, settings.aiTranslateBaseUrl, settings.aiTranslateModel,
                settings.aiTranslatePrompt, settings.aiTranslateThinkingMode,
            )
        }
    }
}

/** 列表标题译文只用于展示，不改 Novel.title，避免污染详情、导出与 feed 缓存（#1372）。 */
class NovelTitleTranslationViewModel : ViewModel() {
    data class State(val running: Boolean = false, val error: Event<Exception>? = null)

    private val _state = MutableLiveData(State())
    val state: LiveData<State> = _state
    private val translations = mutableMapOf<String, String>()
    private var translationKey: NovelTranslationKey? = null
    private var job: Job? = null
    var shown: Boolean = false
        private set

    fun title(original: String?, lang: String): String =
        if (shown && lang == translationKey?.targetLang) translations[original] ?: original.orEmpty() else original.orEmpty()

    fun hide() {
        job?.cancel()
        shown = false
        _state.value = State()
    }

    fun translate(titles: List<String>, lang: String, engine: Translator) {
        val key = NovelTranslationKey.capture(engine, lang)
        if (_state.value?.running == true && translationKey == key) return
        job?.cancel()
        if (translationKey != key) {
            translations.clear()
            translationKey = key
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
                _state.value = State(error = Event(e))
            }
        }
    }

    /** AI / Google 按请求时的引擎报错；云翻译可能已关闭并回退 Google，交给现有动态分流。 */
    fun translationEngine(): Translator? = translationKey?.translator?.takeUnless { it === CloudTranslator }
}
