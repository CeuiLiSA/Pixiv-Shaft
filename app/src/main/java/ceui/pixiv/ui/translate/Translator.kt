package ceui.pixiv.ui.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/** 流式进度与译文分开；reasoningContent 也可能只是上游发出的状态提示。 */
sealed interface AiTranslatePhase {
    data class Thinking(val reasoningContent: String = "") : AiTranslatePhase
    data object Generating : AiTranslatePhase
}

interface Translator {
    suspend fun translate(
        input: String,
        outputLang: String,
        onPhase: ((AiTranslatePhase) -> Unit)? = null,
    ): String

    /**
     * 翻一批文本。默认按 translate() 逐条调,子类可覆写做真 batch
     * (比如 Google web 端点把多条 join 成单次 HTTP)。
     * - onItem: 单条完成后回调,用于 LiveData 增量推送
     * - onProgress: 每完成一段后回调 (done, total),用于按钮进度
     * - onPhase: 流式阶段的实时回调(思考中/生成中),非流式实现可以不回调
     * - onRequestSent: 请求即将向远端发出(POST 已就绪)时回调。Google 免费端点不需要,
     *   AI 引擎用于「退出二次确认」——POST 出去后 Token 已经开烧,退出会浪费。
     */
    suspend fun translateBatch(
        inputs: List<String>,
        outputLang: String,
        onItem: ((index: Int, translated: String) -> Unit)? = null,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        onPhase: ((AiTranslatePhase) -> Unit)? = null,
        onRequestSent: (() -> Unit)? = null,
    ): List<String> {
        val out = mutableListOf<String>()
        for ((i, text) in inputs.withIndex()) {
            coroutineContext.ensureActive()
            onRequestSent?.invoke()
            val zh = try {
                translate(text, outputLang, onPhase)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Translator: item %d failed", i)
                ""
            }
            out.add(zh)
            if (zh.isNotEmpty()) onItem?.invoke(i, zh)
            onProgress?.invoke(i + 1, inputs.size)
        }
        return out
    }
}
