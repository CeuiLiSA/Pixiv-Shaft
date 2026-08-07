package ceui.pixiv.ui.translate

import ceui.lisa.activities.Shaft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 自定义 AI 翻译(#975):走用户配置的 OpenAI 兼容 chat/completions 接口的 [Translator] 实现。
 *
 * base URL 可以指向任何兼容端点 — OpenAI / DeepSeek 等云服务,也可以是 Ollama、
 * llama.cpp server(Sakura 模型)这类本地部署;API key 对本地服务可空。
 * 配置读 [Shaft.sSettings],在设置页「自定义 AI 翻译」里填。
 *
 * batch 模式把整页 OCR 文本装进一个 JSON 数组、要求模型原样回一个等长 JSON 数组,
 * 把 N 次请求压成 1 次(LLM 单次往返比 Google gtx 慢得多,逐条翻一页漫画等不起)。
 * 选 JSON 数组而不是 \n 拼接是因为 OCR 文本自身可能含换行,行数协议会错位。
 * 数组长度对不上 / 解析失败 → 退化为逐条,和 [GoogleWebTranslator] 同款兜底。
 */
object AiTranslator : Translator {

    private const val MAX_BATCH_CHARS = 3000
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** 引擎开关:启用 + base URL + 模型名齐了才算激活,否则调用方继续走 Google。 */
    fun isActive(): Boolean {
        val s = Shaft.sSettings
        return s.isAiTranslateEnabled &&
            s.aiTranslateBaseUrl.isNotBlank() &&
            s.aiTranslateModel.isNotBlank()
    }

    override suspend fun translate(input: String, outputLang: String): String = withContext(Dispatchers.IO) {
        if (input.isBlank()) return@withContext input
        callChatCompletion(
            systemPrompt = systemPromptFor(outputLang),
            userContent = input,
        ).trim()
    }

    override suspend fun translateBatch(
        inputs: List<String>,
        outputLang: String,
        onItem: ((Int, String) -> Unit)?,
        onProgress: ((Int, Int) -> Unit)?,
    ): List<String> = withContext(Dispatchers.IO) {
        if (inputs.isEmpty()) return@withContext emptyList()

        val results = MutableList(inputs.size) { "" }
        val ranges = chunkByCharLimit(inputs, MAX_BATCH_CHARS)

        var done = 0
        for ((from, to) in ranges) {
            coroutineContext.ensureActive()
            val slice = inputs.subList(from, to)
            var batchOk = false
            try {
                val payload = JSONArray().apply { slice.forEach { put(it) } }
                val reply = callChatCompletion(
                    systemPrompt = batchSystemPromptFor(outputLang),
                    userContent = payload.toString(),
                )
                val lines = parseJsonArrayReply(reply)
                if (lines != null && lines.size == slice.size) {
                    for (j in slice.indices) {
                        val idx = from + j
                        results[idx] = lines[j]
                        if (lines[j].isNotEmpty()) onItem?.invoke(idx, lines[j])
                    }
                    batchOk = true
                } else {
                    Timber.w(
                        "AiTranslator: batch reply mismatch (%d → %s), per-item fallback",
                        slice.size, lines?.size?.toString() ?: "unparsable"
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "AiTranslator: batch [%d,%d) failed, per-item fallback", from, to)
            }
            if (!batchOk) {
                for (j in slice.indices) {
                    coroutineContext.ensureActive()
                    val idx = from + j
                    val zh = runCatching { translate(slice[j], outputLang) }
                        .onFailure { Timber.e(it, "AiTranslator: item %d failed", idx) }
                        .getOrNull().orEmpty()
                    results[idx] = zh
                    if (zh.isNotEmpty()) onItem?.invoke(idx, zh)
                }
            }
            done += (to - from)
            onProgress?.invoke(done, inputs.size)
        }

        results
    }

    /**
     * 设置页「测试」按钮用:绕过 isActive 开关,直接拿传入配置翻一句样例。
     * 成功返回译文,失败抛异常(信息给 UI 展示)。
     */
    suspend fun testConfig(baseUrl: String, apiKey: String, model: String, prompt: String): String =
        withContext(Dispatchers.IO) {
            callChatCompletion(
                systemPrompt = prompt.ifBlank { systemPromptFor(appTranslateTargetLang()) },
                userContent = "こんにちは、世界！",
                overrideBaseUrl = baseUrl,
                overrideApiKey = apiKey,
                overrideModel = model,
            ).trim()
        }

    private fun systemPromptFor(outputLang: String): String {
        val custom = Shaft.sSettings.aiTranslatePrompt
        if (custom.isNotBlank()) return custom
        return "You are a professional translator. Translate the user's text into " +
            langName(outputLang) +
            ". Output ONLY the translation, no explanations, no quotes."
    }

    private fun batchSystemPromptFor(outputLang: String): String {
        val base = systemPromptFor(outputLang)
        return base + "\nThe user sends a JSON array of strings. Translate each element and " +
            "reply with ONLY a JSON array of the same length, same order, no markdown fences."
    }

    /** 提示词里用英文语言名,比裸语言码对小模型友好。 */
    private fun langName(lang: String): String = when (lang.lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "Simplified Chinese"
        "zh-tw", "zh-hant" -> "Traditional Chinese"
        "en" -> "English"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "ru" -> "Russian"
        "tr" -> "Turkish"
        else -> lang
    }

    private fun callChatCompletion(
        systemPrompt: String,
        userContent: String,
        overrideBaseUrl: String? = null,
        overrideApiKey: String? = null,
        overrideModel: String? = null,
    ): String {
        val settings = Shaft.sSettings
        val endpoint = normalizeEndpoint(overrideBaseUrl ?: settings.aiTranslateBaseUrl)
        val apiKey = overrideApiKey ?: settings.aiTranslateApiKey
        val model = overrideModel ?: settings.aiTranslateModel

        // 不带 temperature:推理系模型(o 系列/gpt-5 家族)对非默认值直接 400,缺省值全家通用
        val body = JSONObject().apply {
            put("model", model)
            put("stream", false)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userContent))
            })
        }
        val builder = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        client.newCall(builder.build()).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 4xx 的 body 里通常带着可读的错误(key 无效/模型不存在/欠费),截一段给用户看
                throw RuntimeException("HTTP ${resp.code}: ${respBody.take(200)}")
            }
            val content = JSONObject(respBody)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
            if (content.isNullOrBlank()) {
                throw RuntimeException("empty completion: ${respBody.take(200)}")
            }
            return content
        }
    }

    /** 用户可能填 base(…/v1)也可能直接贴完整端点,都认。 */
    private fun normalizeEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    /** 模型没管住嘴包了 ```json 围栏也认;解析失败返回 null 走逐条兜底。 */
    private fun parseJsonArrayReply(reply: String): List<String>? {
        val stripped = reply.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            val arr = JSONArray(stripped)
            List(arr.length()) { arr.optString(it, "") }
        } catch (e: Exception) {
            null
        }
    }

    /** 与 [GoogleWebTranslator] 同款:按累计字符数切 [from, to) 区间。 */
    private fun chunkByCharLimit(inputs: List<String>, limit: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = 0
        var size = 0
        for (i in inputs.indices) {
            val len = inputs[i].length + 1
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
}

/** 业务侧统一取翻译器:自定义 AI 配好并启用 → AI,否则内置 Google web 端点。 */
fun currentTranslator(): Translator =
    if (AiTranslator.isActive()) AiTranslator else GoogleWebTranslator
