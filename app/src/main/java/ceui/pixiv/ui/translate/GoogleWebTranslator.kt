package ceui.pixiv.ui.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 走免费 web 端点 translate.googleapis.com 的 Translator 实现。
 * 国内需要梯子;失败抛异常,由调用方提示。
 *
 * batch 模式把整页 OCR 文本用 \n 拼成单次 POST,把 N 次 HTTP 压成 1 次,
 * 避开 429 限频。走 POST 是因为日文 URL 编码 1 字符 = 9 字符,几百字的 q
 * 走 GET 会撞 414 URI Too Long。
 *
 * 兜底:
 * - 响应按 \n 切回数量对不上(OCR 文本含内嵌 \n 或被 Google 分句器合并)
 * - HTTP / 解析失败
 * 这两种情况都退化为逐条 callGtx。
 */
object GoogleWebTranslator : Translator {

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val SOURCE_LANG = "ja"
    private const val MAX_Q_CHARS = 4000 // gtx q 实际限制 ~5000 字符,留余量
    private const val JOIN_SEP = "\n"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun translate(
        input: String,
        outputLang: String,
        onPhase: ((AiTranslatePhase) -> Unit)?,
    ): String = withContext(Dispatchers.IO) {
        if (input.isBlank()) return@withContext input
        callGtx(input, normalizeTargetLang(outputLang))
    }

    override suspend fun translateBatch(
        inputs: List<String>,
        outputLang: String,
        onItem: ((Int, String) -> Unit)?,
        onProgress: ((Int, Int) -> Unit)?,
        onPhase: ((AiTranslatePhase) -> Unit)?,
        // Google 免费端点不烧 Token,不需要「退出二次确认」信号,这里显式忽略。
        onRequestSent: (() -> Unit)?,
    ): List<String> = withContext(Dispatchers.IO) {
        if (inputs.isEmpty()) return@withContext emptyList()

        val results = MutableList(inputs.size) { "" }
        val tl = normalizeTargetLang(outputLang)
        val ranges = chunkByCharLimit(inputs, MAX_Q_CHARS)
        var lastError: Exception? = null

        var done = 0
        for ((from, to) in ranges) {
            coroutineContext.ensureActive()
            val slice = inputs.subList(from, to)
            var batchOk = false
            try {
                val joined = slice.joinToString(JOIN_SEP)
                val translated = callGtx(joined, tl)
                val lines = translated.split(JOIN_SEP)
                Timber.d(
                    "GoogleWebTranslator: chunk[%d,%d) sent %d lines, got %d lines back",
                    from, to, slice.size, lines.size
                )
                if (lines.size == slice.size) {
                    for (j in slice.indices) {
                        val idx = from + j
                        results[idx] = lines[j]
                        // 把每一对 input → output 都打出来,人眼对齐
                        Timber.d(
                            "GoogleWebTranslator: [%d] \"%s\" → \"%s\"",
                            idx, slice[j].take(40), lines[j].take(40)
                        )
                        if (lines[j].isNotEmpty()) onItem?.invoke(idx, lines[j])
                    }
                    batchOk = true
                } else {
                    Timber.w(
                        "GoogleWebTranslator: split mismatch (%d → %d), per-item fallback",
                        slice.size, lines.size
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "GoogleWebTranslator: batch [%d,%d) failed, per-item fallback", from, to)
                lastError = e
            }
            if (!batchOk) {
                // 切回数量对不上 / HTTP / 解析挂了都走这条:每条单独跑,失败留空串
                for (j in slice.indices) {
                    coroutineContext.ensureActive()
                    val idx = from + j
                    val zh = try {
                        callGtx(slice[j], tl)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.e(e, "GoogleWebTranslator: item %d failed", idx)
                        lastError = e
                        ""
                    }
                    results[idx] = zh
                    if (zh.isNotEmpty()) onItem?.invoke(idx, zh)
                }
            }
            done += (to - from)
            onProgress?.invoke(done, inputs.size)
        }

        if (results.all { it.isBlank() }) {
            throw lastError ?: RuntimeException("google translate: all items failed without an exception")
        }

        results
    }

    /**
     * 按累计字符数把 inputs 切成若干个 [from, to) 区间,每段 join 后不超过 limit。
     * 单条本身超 limit 也会自己占一段。
     */
    private fun chunkByCharLimit(inputs: List<String>, limit: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = 0
        var size = 0
        for (i in inputs.indices) {
            val len = inputs[i].length + JOIN_SEP.length
            if (size + len > limit && i > start) {
                out.add(start to i)
                start = i
                size = 0
            }
            size += len
        }
        if (start < inputs.size) out.add(start to inputs.size)
        return out
    }

    private suspend fun callGtx(text: String, targetLang: String): String {
        // POST + form body,FormBody 自动 URL 编码 q 值,避免 GET URL 撑爆
        val form = FormBody.Builder()
            .add("client", "gtx")
            .add("sl", SOURCE_LANG)
            .add("tl", targetLang)
            .add("dt", "t")
            .add("q", text)
            .build()
        val req = Request.Builder()
            .url(ENDPOINT)
            .post(form)
            .header("User-Agent", "Mozilla/5.0")
            .build()
        return awaitOkHttpCall(client.newCall(req)) { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("google translate http ${resp.code}")
            }
            parseResponse(resp.body?.string().orEmpty())
        }
    }

    private fun normalizeTargetLang(lang: String): String = when (lang.lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "zh-CN"
        "zh-tw", "zh-hant" -> "zh-TW"
        else -> lang
    }

    /**
     * 响应是嵌套数组,首层 [0] 是分句翻译数组,每条是 [translated, original, ...]。
     * 把每条 translated 拼起来,内嵌的 \n 会自然保留。
     */
    private fun parseResponse(body: String): String {
        val outer = JSONArray(body)
        val segments = outer.optJSONArray(0) ?: return ""
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONArray(i) ?: continue
            val translated = seg.optString(0, "")
            sb.append(translated)
        }
        return sb.toString()
    }
}
