package ceui.pixiv.ui.translate

import ceui.lisa.activities.Shaft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * 自定义 AI 翻译(#975):走用户配置的 OpenAI 兼容 chat/completions 接口的 [Translator] 实现。
 *
 * base URL 可以指向任何兼容端点 — OpenAI / DeepSeek 等云服务,也可以是 Ollama、
 * llama.cpp server(Sakura 模型)这类本地部署;API key 对本地服务可空。
 * 配置读 [Shaft.sSettings],在设置页「自定义 AI 翻译」里填。
 *
 * batch 模式把整页 OCR 文本装进一个 JSON 数组、要求模型原样回一个等长 JSON 数组,
 * 把 N 次请求压成少数几次(LLM 单次往返比 Google gtx 慢得多,逐条翻一页漫画等不起);
 * 多个 chunk **并发**发出([REQUEST_CONCURRENCY] 封顶),按序 await 回调,保证
 * onItem/onProgress 仍在单协程里串行触发 — 调用方(ImageTranslationViewModel)用的
 * 是非线程安全的 mutableMap,回调并发化会引入竞态。
 * 选 JSON 数组而不是 \n 拼接是因为 OCR 文本自身可能含换行,行数协议会错位。
 * 数组长度对不上 / 解析失败 → 该 chunk 退化为逐条(同样并发),和 [GoogleWebTranslator]
 * 同款兜底思路。
 *
 * 瞬时故障(网络 IO / 429 / 5xx)自动重试一次,带 1s 退避;4xx 配置类错误不重试,
 * 并从 OpenAI 标准错误体里抽 error.message 给用户人话提示。
 */
object AiTranslator : Translator {

    private const val MAX_BATCH_CHARS = 3000

    /**
     * 全局并发请求上限。OkHttp 的 dispatcher 限流只管 enqueue 的异步调用,同步
     * execute() 不设限 — 不加闸门的话长漫画页会同时开出几十条连接。
     */
    private const val REQUEST_CONCURRENCY = 4

    private const val RETRY_DELAY_MS = 1_000L

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val requestSemaphore = Semaphore(REQUEST_CONCURRENCY)

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
        val batchPrompt = batchSystemPromptFor(outputLang)

        coroutineScope {
            // 所有 chunk 并发发出(semaphore 封顶),按序 await → 回调保持单协程串行
            val chunkJobs = ranges.map { (from, to) ->
                val slice = inputs.subList(from, to)
                async {
                    try {
                        val payload = JSONArray().apply { slice.forEach { put(it) } }
                        val reply = requestSemaphore.withPermit {
                            callChatCompletion(batchPrompt, payload.toString())
                        }
                        parseJsonArrayReply(reply)?.takeIf { it.size == slice.size }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e, "AiTranslator: batch [%d,%d) failed, per-item fallback", from, to)
                        null
                    }
                }
            }

            var done = 0
            for ((i, range) in ranges.withIndex()) {
                val (from, to) = range
                val slice = inputs.subList(from, to)
                val lines = chunkJobs[i].await()
                if (lines != null) {
                    for (j in slice.indices) {
                        val idx = from + j
                        results[idx] = lines[j]
                        if (lines[j].isNotEmpty()) onItem?.invoke(idx, lines[j])
                    }
                } else {
                    // chunk 整体失败 / 协议没对齐 → 该段逐条兜底,同样并发、按序回调
                    val itemJobs = slice.map { text ->
                        async {
                            try {
                                requestSemaphore.withPermit {
                                    callChatCompletion(systemPromptFor(outputLang), text).trim()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.e(e, "AiTranslator: fallback item failed")
                                ""
                            }
                        }
                    }
                    for (j in slice.indices) {
                        coroutineContext.ensureActive()
                        val idx = from + j
                        val zh = itemJobs[j].await()
                        results[idx] = zh
                        if (zh.isNotEmpty()) onItem?.invoke(idx, zh)
                    }
                }
                done += (to - from)
                onProgress?.invoke(done, inputs.size)
            }
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

    /**
     * 设置页「获取模型列表」用:GET {base}/models,返回模型 id 列表(原序)。
     * 失败抛异常(信息给 UI 展示)。
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val endpoint = normalizeModelsEndpoint(baseUrl)
        val builder = Request.Builder().url(endpoint).get()
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        client.newCall(builder.build()).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException(apiErrorMessage(resp.code, respBody))
            }
            val data = JSONObject(respBody).optJSONArray("data")
                ?: throw IOException("unexpected response: ${respBody.take(200)}")
            buildList {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) add(id)
                }
            }
        }
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

    /**
     * 带重试的 chat/completions 调用:网络 IO / 429 / 5xx 重试一次([RETRY_DELAY_MS] 退避),
     * 其余 4xx 是配置错误(key 无效/模型不存在),重试无意义直接抛。
     */
    private suspend fun callChatCompletion(
        systemPrompt: String,
        userContent: String,
        overrideBaseUrl: String? = null,
        overrideApiKey: String? = null,
        overrideModel: String? = null,
    ): String {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return doCallChatCompletion(systemPrompt, userContent, overrideBaseUrl, overrideApiKey, overrideModel)
            } catch (e: RetryableApiException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt == 0) {
                Timber.w(lastError, "AiTranslator: transient failure, retrying")
                delay(RETRY_DELAY_MS)
            }
        }
        throw lastError!!
    }

    private fun doCallChatCompletion(
        systemPrompt: String,
        userContent: String,
        overrideBaseUrl: String?,
        overrideApiKey: String?,
        overrideModel: String?,
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
                val message = apiErrorMessage(resp.code, respBody)
                if (resp.code == 429 || resp.code >= 500) throw RetryableApiException(message)
                // 4xx 是配置错误(key 无效/模型不存在):特意不用 IOException,
                // 否则会被上面的重试层当瞬时故障捕获再打一次
                throw ApiConfigException(message)
            }
            val content = JSONObject(respBody)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
            if (content.isNullOrBlank()) {
                throw IOException("empty completion: ${respBody.take(200)}")
            }
            return content
        }
    }

    private class RetryableApiException(message: String) : IOException(message)

    private class ApiConfigException(message: String) : RuntimeException(message)

    /** 提示词里用英文语言名,比裸语言码对小模型友好。 */
    internal fun langName(lang: String): String = when (lang.lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "Simplified Chinese"
        "zh-tw", "zh-hant" -> "Traditional Chinese"
        "en" -> "English"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "ru" -> "Russian"
        "tr" -> "Turkish"
        else -> lang
    }

    /**
     * OpenAI 标准错误体是 {"error":{"message":...}},抽出人话;非标准体退回截断原文。
     */
    internal fun apiErrorMessage(code: Int, body: String): String {
        val message = try {
            JSONObject(body).optJSONObject("error")?.optString("message")
        } catch (e: Exception) {
            null
        }
        return if (!message.isNullOrBlank()) {
            "HTTP $code: $message"
        } else {
            "HTTP $code: ${body.take(200)}"
        }
    }

    /** 用户可能填 base(…/v1)也可能直接贴完整端点,都认。 */
    internal fun normalizeEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    /** models 端点:容忍用户贴了完整 chat/completions 地址。 */
    internal fun normalizeModelsEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions")
        return "$trimmed/models"
    }

    /** 模型没管住嘴包了 ```json 围栏也认;解析失败返回 null 走逐条兜底。 */
    internal fun parseJsonArrayReply(reply: String): List<String>? {
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
    internal fun chunkByCharLimit(inputs: List<String>, limit: Int): List<Pair<Int, Int>> {
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
