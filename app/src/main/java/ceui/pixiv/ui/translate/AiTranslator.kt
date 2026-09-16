package ceui.pixiv.ui.translate

import ceui.lisa.activities.Shaft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellableContinuation
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

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
 * 传输层(设置页可配):
 * - 流式(默认开):stream=true + okhttp-sse(EventSource)标准解析,thinking 阶段经
 *   [AiTranslatePhase] 回调给 UI,
 *   响应期间持续有字节,readTimeout 不会被思考时间误触发;失败自动降级非流式。
 * - 思考参数:默认不加(平台默认);DeepSeek 用 thinking.type=disabled;
 *   SiliconFlow/千问用 enable_thinking=false;OpenAI 系推理模型用 reasoning_effort=low。
 * - readTimeout 秒数可配,默认 120。
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

    private const val MIN_READ_TIMEOUT_SECONDS = 30

    private const val MAX_READ_TIMEOUT_SECONDS = 600

    /** 失败响应最多读这么多错误体,拿错误消息用;超过就截断(消息一般都在开头)。 */
    private const val MAX_ERROR_BODY_BYTES = 8192

    /** 失败响应体读取的兜底超时(秒):网关不关流时快速失败,别把「翻译中」拖成无限。 */
    private const val ERROR_BODY_READ_TIMEOUT_SECONDS = 10L

    /** 非流式成功响应逐段读取的块大小(字节)。 */
    private const val JSON_READ_CHUNK_BYTES = 8192

    /** 获取模型列表/测试翻译的 connect 固定短超时(秒),不跟翻译请求共享。 */
    private const val QUICK_REQUEST_TIMEOUT_SECONDS = 3L

    /** DNS 解析超时(秒):OkHttp 的 connectTimeout 管不到 DNS,必须单独掐断。 */
    private const val DNS_RESOLVE_TIMEOUT_SECONDS = 1L

    /** 真实翻译请求的 connect 超时(秒)。 */
    private const val REAL_CONNECT_TIMEOUT_SECONDS = 15L

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val requestSemaphore = Semaphore(REQUEST_CONCURRENCY)

    /** 客户端缓存 key:连接超时 + 读超时 + DNS 一起决定一个 OkHttpClient。 */
    private data class ClientKey(val connectSeconds: Long, val readSeconds: Int, val dns: Dns)

    private val clients = ConcurrentHashMap<ClientKey, OkHttpClient>()

    /** 统一取客户端;设置页改了读超时只影响新请求,不重建旧连接。 */
    private fun clientFor(connectSeconds: Long, readSeconds: Int, dns: Dns): OkHttpClient =
        clients.computeIfAbsent(ClientKey(connectSeconds, readSeconds, dns)) {
            OkHttpClient.Builder()
                .connectTimeout(connectSeconds, TimeUnit.SECONDS)
                .readTimeout(readSeconds.toLong(), TimeUnit.SECONDS)
                .dns(dns)
                .build()
        }

    /**
     * 带 1 秒超时的 DNS:模型列表、测试翻译共用。系统解析失败(如 DNS 被污染/无响应)时
     * netd 可能要几十秒才报 UnknownHostException,必须在这里独立掐断。
     */
    private val dnsExecutor: ExecutorService = Executors.newCachedThreadPool()

    private val quickDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val future = dnsExecutor.submit(Callable { Dns.SYSTEM.lookup(hostname) })
            return try {
                future.get(DNS_RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                throw UnknownHostException("DNS timeout resolving $hostname")
            }
        }
    }

    /**
     * 模型列表专用客户端:connect/read 固定 3 秒、DNS 1 秒,不跟翻译请求共享预设的读超时。
     * 端点不通、key 错、域名解析失败时几秒内就反馈,而不是等满 readTimeout。
     */
    private val modelsClient: OkHttpClient by lazy {
        clientFor(QUICK_REQUEST_TIMEOUT_SECONDS, QUICK_REQUEST_TIMEOUT_SECONDS.toInt(), quickDns)
    }

    /** 测试翻译专用客户端:connect 3 秒 + 预设读超时 + 1 秒 DNS,反馈快且贴合真实翻译。 */
    private fun testClientFor(readTimeoutSeconds: Int): OkHttpClient =
        clientFor(QUICK_REQUEST_TIMEOUT_SECONDS, readTimeoutSeconds, quickDns)

    /** 引擎开关:启用 + base URL + 模型名齐了才算激活,否则调用方继续走 Google。 */
    fun isActive(): Boolean {
        val s = Shaft.sSettings
        return s.isAiTranslateEnabled &&
            s.aiTranslateBaseUrl.isNotBlank() &&
            s.aiTranslateModel.isNotBlank()
    }

    override suspend fun translate(
        input: String,
        outputLang: String,
        onPhase: ((AiTranslatePhase) -> Unit)?,
    ): String = withContext(Dispatchers.IO) {
        if (input.isBlank()) return@withContext input
        callChatCompletion(
            systemPrompt = systemPromptFor(outputLang),
            userContent = input,
            onPhase = onPhase,
        ).trim()
    }

    override suspend fun translateBatch(
        inputs: List<String>,
        outputLang: String,
        onItem: ((Int, String) -> Unit)?,
        onProgress: ((Int, Int) -> Unit)?,
        onPhase: ((AiTranslatePhase) -> Unit)?,
        onRequestSent: (() -> Unit)?,
    ): List<String> = withContext(Dispatchers.IO) {
        if (inputs.isEmpty()) return@withContext emptyList()

        val results = MutableList(inputs.size) { "" }
        val ranges = chunkByCharLimit(inputs, MAX_BATCH_CHARS)
        val batchPrompt = batchSystemPromptFor(outputLang)
        val lastError = AtomicReference<Exception?>(null)
        // 任一 chunk 拿到 4xx 配置错误(key 无效/模型不存在)后,所有条目共用同一份配置,
        // 逐条兜底必败且会放大成 N 次请求,直接跳过整批的逐条兜底。
        val configErrorHit = AtomicBoolean(false)

        coroutineScope {
            // 并发 chunk 的阶段聚合:任一请求在思考 → 思考中,任一在生成 → 生成中(只升不降)
            val phaseAggregator = PhaseAggregator(onPhase)

            // 所有 chunk 并发发出(semaphore 封顶),按序 await → 回调保持单协程串行
            val chunkJobs = ranges.map { (from, to) ->
                val slice = inputs.subList(from, to)
                async {
                    try {
                        val payload = JSONArray().apply { slice.forEach { put(it) } }
                        val reply = requestSemaphore.withPermit {
                            callChatCompletion(
                                batchPrompt, payload.toString(),
                                onPhase = phaseAggregator::report,
                                onRequestSent = onRequestSent,
                            )
                        }
                        parseJsonArrayReply(reply)?.takeIf { it.size == slice.size }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiConfigException) {
                        // 配置错误重试无意义,和 callChatCompletion 的 4xx 不重试同策略
                        configErrorHit.set(true)
                        lastError.set(e)
                        Timber.w(e, "AiTranslator: batch [%d,%d) config error, skip per-item fallback", from, to)
                        null
                    } catch (e: Exception) {
                        Timber.w(e, "AiTranslator: batch [%d,%d) failed, per-item fallback", from, to)
                        lastError.set(e)
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
                } else if (!configErrorHit.get()) {
                    // chunk 整体失败 / 协议没对齐 → 该段逐条兜底,同样并发、按序回调
                    val itemJobs = slice.map { text ->
                        async {
                            try {
                                requestSemaphore.withPermit {
                                    callChatCompletion(
                                        systemPromptFor(outputLang),
                                        text,
                                        onPhase = phaseAggregator::report,
                                        onRequestSent = onRequestSent,
                                    ).trim()
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.e(e, "AiTranslator: fallback item failed")
                                lastError.set(e)
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

        if (results.all { it.isBlank() }) {
            throw lastError.get() ?: IOException("AiTranslator: all items failed without an exception")
        }

        results
    }

    /**
     * 设置页「测试」按钮用:绕过 isActive 开关,直接拿传入配置翻一句样例。
     * 使用真实提示词(自定义 prompt 或内置翻译提示词),并校验模型是否按指令返回:
     * 原样回显输入视为未遵循指令,直接判失败。
     *
     * [forceStreaming] 供单测显式指定传输通道(默认 null = 跟随 Settings),
     * 使 MockWebServer 能覆盖 SSE 协议层而不依赖 Android 运行时。
     */
    suspend fun testConfig(
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        forceStreaming: Boolean? = null,
        onPhase: ((AiTranslatePhase) -> Unit)? = null,
    ): String =
        withContext(Dispatchers.IO) {
            val input = "こんにちは、世界！"
            // 目标语言算一次:提示词回退和指令遵循校验共用;单测无 AppLocales 环境时回退 null
            val targetLang = runCatching { appTranslateTargetLang() }.getOrNull()
            val translated = callChatCompletion(
                systemPrompt = prompt.ifBlank { systemPromptFor(targetLang ?: "en") },
                userContent = input,
                overrideBaseUrl = baseUrl,
                overrideApiKey = apiKey,
                overrideModel = model,
                client = testClientFor(configuredReadTimeout()),
                forceStreaming = forceStreaming,
                onPhase = onPhase,
            ).trim()
            // 指令遵循校验:返回内容里带着原文 = 模型没执行翻译(回显/夹带解释),不算成功;
            // 目标语言就是日文时,同语言"翻译"允许保留原文,跳过该检查。
            if (targetLang != "ja" && translated.contains(input)) {
                throw IOException("模型没有如期按提示词指令进行翻译，输出了原文！")
            }
            translated
        }

    /**
     * 设置页「获取模型列表」用:GET {base}/models,返回模型 id 列表(原序)。
     * 独立走 [modelsClient] 的固定短超时(connect/read 3s、DNS 1s),不跟翻译请求的预设读超时。
     * 失败抛异常(信息给 UI 展示)。
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val endpoint = normalizeModelsEndpoint(baseUrl)
        val builder = Request.Builder().url(endpoint).get()
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        Timber.d("AiTranslator: GET %s", endpoint)
        val startNanos = System.nanoTime()
        modelsClient.newCall(builder.build()).execute().use { resp ->
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            if (!resp.isSuccessful) {
                val errorBody = readBodyQuietly(resp, QUICK_REQUEST_TIMEOUT_SECONDS.toInt())
                Timber.d("AiTranslator: HTTP %d %s in %d ms body=%s", resp.code, endpoint, elapsedMs, errorBody)
                throw IOException(apiErrorMessage(resp.code, errorBody))
            }
            val respBody = readCompletionJsonQuietly(resp, QUICK_REQUEST_TIMEOUT_SECONDS.toInt())
            Timber.d("AiTranslator: HTTP %d %s in %d ms body=%s", resp.code, endpoint, elapsedMs, respBody)
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

    /** 单测等无 Settings 的场景回退默认值。 */
    private fun configuredReadTimeout(): Int =
        (Shaft.sSettings?.aiTranslateReadTimeoutSeconds ?: 120)
            .coerceIn(MIN_READ_TIMEOUT_SECONDS, MAX_READ_TIMEOUT_SECONDS)

    /**
     * Android org.json 的 optString 会把 JSON 字面量 null 转成字符串 "null",
     * 思考型模型流式 chunk 的 delta 常带 content/reasoning_content: null,
     * 必须把 null 当缺失处理,否则结果会塞进一串 "nullnullnull…"。
     */
    private fun JSONObject.optStringOrEmpty(name: String): String =
        if (isNull(name)) "" else optString(name)

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
        onPhase: ((AiTranslatePhase) -> Unit)? = null,
        client: OkHttpClient? = null,
        forceStreaming: Boolean? = null,
        onRequestSent: (() -> Unit)? = null,
    ): String {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return doCallChatCompletion(
                    systemPrompt, userContent, overrideBaseUrl, overrideApiKey, overrideModel,
                    onPhase, client, forceStreaming, onRequestSent,
                )
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

    private suspend fun doCallChatCompletion(
        systemPrompt: String,
        userContent: String,
        overrideBaseUrl: String?,
        overrideApiKey: String?,
        overrideModel: String?,
        onPhase: ((AiTranslatePhase) -> Unit)?,
        client: OkHttpClient? = null,
        forceStreaming: Boolean? = null,
        onRequestSent: (() -> Unit)? = null,
    ): String {
        val settings = Shaft.sSettings
        val endpoint = normalizeEndpoint(overrideBaseUrl ?: settings?.aiTranslateBaseUrl ?: "")
        val apiKey = overrideApiKey ?: settings?.aiTranslateApiKey ?: ""
        val model = overrideModel ?: settings?.aiTranslateModel ?: ""
        val thinkingMode = settings?.aiTranslateThinkingMode ?: 0
        val streaming = forceStreaming ?: (settings?.isAiTranslateStreaming ?: false)
        val readTimeout = configuredReadTimeout()

        // 不带 temperature:推理系模型(o 系列/gpt-5 家族)对非默认值直接 400,缺省值全家通用。
        // 思考参数按设置页选择显式加:默认不加(使用平台默认);DeepSeek 用 thinking.type=disabled;
        // SiliconFlow/千问用 enable_thinking=false;OpenAI 系推理模型用 reasoning_effort=low。
        val body = JSONObject().apply {
            put("model", model)
            when (thinkingMode) {
                1 -> put("thinking", JSONObject().put("type", "disabled"))
                2 -> put("enable_thinking", false)
                3 -> put("reasoning_effort", "low")
            }
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userContent))
            })
        }

        // 请求体已就绪,马上要向 AI 接口发 POST(可能已烧 Token)。
        // 此刻通知业务侧:之后用户退出要弹「二次确认」,防止手滑浪费 Token。
        onRequestSent?.invoke()

        if (streaming) {
            return try {
                streamChatCompletion(endpoint, apiKey, model, body, readTimeout, onPhase, includeUsage = true, client = client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiConfigException) {
                // 个别严格网关不认 stream_options.include_usage,去掉重试一次流式
                if (e.code != 400) throw e
                Timber.w(e, "AiTranslator: stream_options rejected, retry stream without include_usage")
                try {
                    streamChatCompletion(endpoint, apiKey, model, body, readTimeout, onPhase, includeUsage = false, client = client)
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    // 仍失败(如网关连 stream=true 都不认)→降级非流式
                    Timber.w(e2, "AiTranslator: stream retry failed, falling back to non-stream")
                    onPhase?.invoke(AiTranslatePhase.Generating)
                    nonStreamChatCompletion(endpoint, apiKey, model, body, readTimeout, client = client)
                }
            } catch (e: Exception) {
                Timber.w(e, "AiTranslator: stream failed, falling back to non-stream")
                onPhase?.invoke(AiTranslatePhase.Generating)
                nonStreamChatCompletion(endpoint, apiKey, model, body, readTimeout, client = client)
            }
        }
        return nonStreamChatCompletion(endpoint, apiKey, model, body, readTimeout, client = client)
    }

    /**
     * 流式(SSE)调用,走 OkHttp 官方 okhttp-sse 的 [EventSource]:
     * 规范层的多行 data 拼接、CR/LF/CRLF、注释/keep-alive 行交给库解析,这里只做协议层
     * (JSON / [DONE] / usage / 阶段回调)。
     *
     * 要点:
     * - okhttp-sse 4.x 没有内置自动重连,断流统一进 onFailure,由外层 retry 层决定重试。
     * - [DONE] 是协议层终止符,库不认:收到后主动 cancel(),防止个别代理不关流导致挂到超时;
     *   因此 cancel 后可能走 onFailure(IOException Canceled),此时按成功收尾。
     * - 协程取消经 suspendCancellableCoroutine 调 eventSource.cancel();取消引发的 onFailure
     *   按取消处理,不会误降级为非流式。
     */
    private suspend fun streamChatCompletion(
        endpoint: String,
        apiKey: String,
        model: String,
        body: JSONObject,
        readTimeout: Int,
        onPhase: ((AiTranslatePhase) -> Unit)?,
        includeUsage: Boolean,
        client: OkHttpClient? = null,
    ): String {
        // 复制 body 再改,避免污染调用方共享对象(失败降级/400 重试时不能带残留参数)
        val requestBody = JSONObject(body.toString()).apply {
            put("stream", true)
            if (includeUsage) {
                put("stream_options", JSONObject().put("include_usage", true))
            }
        }.toString()
        val builder = Request.Builder()
            .url(endpoint)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        // 请求/响应全文打 Timber(debug 可见),便于定位问题;API key 只在 Authorization 头里,不打印。
        Timber.d("AiTranslator: POST %s model=%s stream=true body=%s", endpoint, model, requestBody)
        val startNanos = System.nanoTime()

        return suspendCancellableCoroutine { cont ->
            val eventSource = EventSources.createFactory(
                client ?: clientFor(REAL_CONNECT_TIMEOUT_SECONDS, readTimeout, Dns.SYSTEM)
            )
                .newEventSource(builder.build(), object : EventSourceListener() {
                    private var generating = false
                    private var done = false
                    private var finished = false
                    private var chunks = 0
                    private var usage: JSONObject? = null
                    private val content = StringBuilder()
                    private var reasoningChars = 0
                    private var reasoningPreview = ""

                    override fun onOpen(eventSource: EventSource, response: Response) {
                        Timber.d("AiTranslator: stream opened %s", endpoint)
                    }

                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        if (!cont.isActive || done || finished) return
                        if (data == "[DONE]") {
                            done = true
                            // 部分网关在 [DONE] 后不关流,主动断开避免挂到 readTimeout
                            eventSource.cancel()
                            return
                        }
                        val obj = try {
                            JSONObject(data)
                        } catch (e: Exception) {
                            return
                        }
                        obj.optJSONObject("usage")?.let { usage = it }
                        val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                            ?: return
                        val reasoningDelta = delta.optStringOrEmpty("reasoning_content")
                        if (reasoningDelta.isNotEmpty()) {
                            reasoningChars += reasoningDelta.length
                            if (!generating) {
                                reasoningPreview = (reasoningPreview + reasoningDelta).takeLast(240)
                                    .dropWhile { Character.isLowSurrogate(it) }
                                if (reasoningPreview.isNotBlank()) {
                                    onPhase?.invoke(AiTranslatePhase.Thinking(reasoningPreview.trim()))
                                }
                            }
                        }
                        val contentDelta = delta.optStringOrEmpty("content")
                        if (contentDelta.isNotEmpty()) {
                            content.append(contentDelta)
                            if (!generating) {
                                generating = true
                                onPhase?.invoke(AiTranslatePhase.Generating)
                            }
                        }
                        chunks++
                    }

                    override fun onClosed(eventSource: EventSource) {
                        finishSuccess()
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        // [DONE] 后主动 cancel 会以 IOException(Canceled) 进这里,按成功收尾
                        // finished 防重:服务端恰好在 [DONE] 后同步关流时,onClosed/onFailure 可能各到一次
                        if (finished) return
                        if (done) {
                            finishSuccess()
                            return
                        }
                        val failure = failureForStream(t, response, readTimeout)
                        resumeWithFailure(cont, failure)
                    }

                    private fun finishSuccess() {
                        if (finished) return
                        finished = true
                        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                        val result = content.toString()
                        if (result.isBlank()) {
                            val err = IOException(
                                "empty stream completion: reasoning=$reasoningChars chars, chunks=$chunks"
                            )
                            Timber.w(err, "AiTranslator: empty stream after %d ms", elapsedMs)
                            resumeWithFailure(cont, err)
                            return
                        }
                        Timber.d(
                            "AiTranslator: stream done | chunks=%d content=%d chars reasoning=%d chars",
                            chunks, content.length, reasoningChars
                        )
                        logTokenStats(
                            promptTokens = usage?.optInt("prompt_tokens", -1) ?: -1,
                            completionTokens = usage?.optInt("completion_tokens", -1) ?: -1,
                            reasoningTokens = usage?.optJSONObject("completion_tokens_details")
                                ?.optInt("reasoning_tokens", -1)
                                ?: usage?.optInt("reasoning_tokens", -1)
                                ?: -1,
                            elapsedMs = elapsedMs,
                            reasoningContentLen = reasoningChars,
                        )
                        cont.resumeWith(Result.success(result))
                    }
                })
            // suspendCancellableCoroutine 块必须以 Unit 收尾,顺便持有取消句柄
            val cancellationHandle = cont.invokeOnCancellation { eventSource.cancel() }
        }
    }

    /** 把 EventSource 的失败映射回我们的异常体系:HTTP 非 2xx 按 code 分配置错/可重试错。 */
    private fun failureForStream(t: Throwable?, response: Response?, readTimeoutSeconds: Int): Throwable {
        if (response == null) {
            return t ?: IOException("stream failed")
        }
        if (!response.isSuccessful) {
            val body = readBodyQuietly(response, readTimeoutSeconds)
            return if (body.isNotBlank()) {
                apiExceptionFor(response.code, body)
            } else {
                apiExceptionFor(response.code, t?.message.orEmpty())
            }
        }
        // 200 但 content-type 不是 text/event-stream(EventSource 会拒绝):补上 body 增强提示
        val body = readBodyQuietly(response, readTimeoutSeconds)
        return if (body.isNotBlank()) {
            IOException("${t?.message ?: "stream failed"}: ${body.take(200)}")
        } else {
            t ?: IOException("stream failed")
        }
    }

    private fun resumeWithFailure(cont: CancellableContinuation<String>, failure: Throwable) {
        if (cont.isCancelled) {
            cont.resumeWith(Result.failure(CancellationException("AiTranslator stream cancelled", failure)))
        } else {
            cont.resumeWith(Result.failure(failure))
        }
    }

    /**
     * 失败路径最多读一小段错误体并给短兜底超时:流式在响应头就失败、非流式也能快速失败,
     * 不会因为网关的错误响应不关流(无 Content-Length / chunked 不封尾)而把调用钉死在 readTimeout。
     *
     * 注意不能用 source.readUtf8(n):okio 在字节数不足 n 就 EOF 时会抛 EOFException,
     * 而错误体几乎都小于 n,会把整个 message 吞掉;这里逐段累加、超时也保留已读到的字节。
     */
    private fun readBodyQuietly(response: Response, readTimeoutSeconds: Int): String {
        val body = response.body ?: return ""
        val source = body.source()
        val sink = ByteArray(MAX_ERROR_BODY_BYTES)
        var total = 0
        try {
            // 兜底取「客户端 readTimeout 与固定 10s」的较小值:模型列表 3s 客户端不会被拉到 10s
            val capSeconds = minOf(readTimeoutSeconds.toLong(), ERROR_BODY_READ_TIMEOUT_SECONDS)
            source.timeout().timeout(capSeconds, TimeUnit.SECONDS)
            while (total < sink.size) {
                val read = source.read(sink, total, sink.size - total)
                if (read <= 0) break
                total += read
            }
        } catch (e: Exception) {
            // 超时/连接被掐:已读到的字节仍可用于拼错误消息
            Timber.d("AiTranslator: error body read interrupted after %d bytes: %s", total, e.message)
        } finally {
            runCatching { body.close() }
        }
        return if (total == 0) "" else String(sink, 0, total, Charsets.UTF_8)
    }

    /**
     * 非流式成功 JSON 响应(completion / 模型列表):逐段读入,JSON 一旦完整就提前返回,不等 EOF / chunked 终止块。
     *
     * 部分网关(尤其国内中转)会把完整 JSON 发过来,但不及时关流 / chunked 不封尾,
     * 此时 body.string() 会一直等到 readTimeout —— 表现为「内容收到了还在傻等」。
     * 这里每读一段就尝试解析,闭合的 JSONObject 一到手立即返回。
     * 注意要从累计字节整体解码(不能逐段拼 String),否则 UTF-8 多字节字符跨块会被截成乱码。
     */
    private fun readCompletionJsonQuietly(response: Response, readTimeoutSeconds: Int): String {
        val body = response.body ?: return ""
        val source = body.source()
        val sink = ByteArray(JSON_READ_CHUNK_BYTES)
        val bytes = java.io.ByteArrayOutputStream()
        source.timeout().timeout(readTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        try {
            while (true) {
                val read = source.read(sink, 0, sink.size)
                if (read <= 0) break
                bytes.write(sink, 0, read)
                val text = String(bytes.toByteArray(), Charsets.UTF_8)
                if (isCompleteJsonObject(text)) return text
            }
        } finally {
            runCatching { body.close() }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /** 整段文本能解析成完整 JSONObject,就认为响应内容已到齐。 */
    private fun isCompleteJsonObject(text: String): Boolean = try {
        JSONObject(text)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 非流式 chat/completions 调用。走 [awaitOkHttpCall],协程取消时立即掐断连接——
     * 页面销毁后翻译工作流能及时停,不会阻塞到 readTimeout 才回来
     * (默认 120s,最坏 600s)再误报「翻译失败」。
     */
    private suspend fun nonStreamChatCompletion(
        endpoint: String,
        apiKey: String,
        model: String,
        body: JSONObject,
        readTimeout: Int,
        client: OkHttpClient? = null,
    ): String {
        // 复制 body 再改:流式降级过来时原对象可能已带 stream/stream_options
        val requestBody = JSONObject(body.toString()).apply { put("stream", false) }.toString()
        val builder = Request.Builder().url(endpoint).post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        // 请求/响应全文打 Timber(debug 可见);API key 只在 Authorization 头里,不打印。
        Timber.d("AiTranslator: POST %s model=%s body=%s", endpoint, model, requestBody)
        val startNanos = System.nanoTime()
        val call = (client ?: clientFor(REAL_CONNECT_TIMEOUT_SECONDS, readTimeout, Dns.SYSTEM))
            .newCall(builder.build())
        return awaitOkHttpCall(call) { resp ->
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            if (!resp.isSuccessful) {
                val errorBody = readBodyQuietly(resp, readTimeout)
                Timber.d("AiTranslator: HTTP %d %s in %d ms body=%s", resp.code, endpoint, elapsedMs, errorBody)
                throw apiExceptionFor(resp.code, errorBody)
            }
            val respBody = readCompletionJsonQuietly(resp, readTimeout)
            Timber.d("AiTranslator: HTTP %d %s in %d ms body=%s", resp.code, endpoint, elapsedMs, respBody)
            val root = JSONObject(respBody)
            val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val reasoningContent = message?.optStringOrEmpty("reasoning_content").orEmpty()
            val content = message?.optStringOrEmpty("content")
            if (content.isNullOrBlank()) {
                // 思考型模型把答案留在 reasoning_content、content 为空时会走到这,
                // 打一行带耗时的日志方便确认是不是这种情况。
                Timber.w(
                    "AiTranslator: empty completion after %d ms, reasoning_content=%d chars, body=%s",
                    elapsedMs, reasoningContent.length, respBody
                )
                throw IOException("empty completion: ${respBody.take(200)}")
            }
            logUsageStats(root, elapsedMs, reasoningContent.length)
            content
        }
    }

    /** 4xx 配置错误不重试,429/5xx 可重试;统一从这里构造异常。 */
    private fun apiExceptionFor(code: Int, body: String): Exception {
        val message = apiErrorMessage(code, body)
        return if (code == 429 || code >= 500) {
            RetryableApiException(code, message)
        } else {
            ApiConfigException(code, message)
        }
    }

    /**
     * 非流式下客户端看不到思考/生成的分段进度,只能整请求计时,再按 usage 里的 token
     * 数折算**全程平均**速率:thinking = reasoning_tokens / 总耗时,answer = (completion − reasoning) / 总耗时。
     * OpenAI 系把 reasoning 放在 usage.completion_tokens_details.reasoning_tokens,
     * 部分兼容端点直接给 usage.reasoning_tokens;都没有就只报耗时 + reasoning_content 长度。
     */
    private fun logUsageStats(root: JSONObject, elapsedMs: Long, reasoningContentLen: Int) {
        val usage = root.optJSONObject("usage")
        if (usage == null) {
            logTokenStats(-1, -1, -1, elapsedMs, reasoningContentLen)
            return
        }
        logTokenStats(
            promptTokens = usage.optInt("prompt_tokens", -1),
            completionTokens = usage.optInt("completion_tokens", -1),
            reasoningTokens = usage.optJSONObject("completion_tokens_details")
                ?.optInt("reasoning_tokens", -1)
                ?: usage.optInt("reasoning_tokens", -1),
            elapsedMs = elapsedMs,
            reasoningContentLen = reasoningContentLen,
        )
    }

    private fun logTokenStats(
        promptTokens: Int,
        completionTokens: Int,
        reasoningTokens: Int,
        elapsedMs: Long,
        reasoningContentLen: Int,
    ) {
        val elapsedSec = (elapsedMs / 1000.0).coerceAtLeast(0.001)
        val reasoningLenDesc = if (reasoningContentLen > 0) " | reasoning_content=$reasoningContentLen chars" else ""
        when {
            reasoningTokens > 0 && completionTokens > 0 -> {
                val answerTokens = (completionTokens - reasoningTokens).coerceAtLeast(0)
                Timber.d(
                    "AiTranslator: done %d ms | prompt=%d completion=%d reasoning=%d | " +
                        "thinking avg %.1f tok/s, answer avg %.1f tok/s%s",
                    elapsedMs, promptTokens, completionTokens, reasoningTokens,
                    reasoningTokens / elapsedSec,
                    if (answerTokens > 0) answerTokens / elapsedSec else 0.0,
                    reasoningLenDesc,
                )
            }
            completionTokens > 0 -> Timber.d(
                "AiTranslator: done %d ms | prompt=%d completion=%d | avg %.1f tok/s%s",
                elapsedMs, promptTokens, completionTokens, completionTokens / elapsedSec, reasoningLenDesc,
            )
            else -> Timber.d("AiTranslator: done %d ms%s", elapsedMs, reasoningLenDesc)
        }
    }

    /** 并发 chunk 的阶段聚合:只升不降,任一请求在思考 → 思考中,任一在生成 → 生成中。 */
    internal class PhaseAggregator(private val onPhase: ((AiTranslatePhase) -> Unit)?) {
        private val lock = Any()
        private var maxLevel = 0 // 0 无阶段,1 思考中,2 生成中
        private var lastPhase: AiTranslatePhase? = null

        fun report(phase: AiTranslatePhase) {
            if (onPhase == null) return
            val level = if (phase is AiTranslatePhase.Thinking) 1 else 2
            synchronized(lock) {
                if (level >= maxLevel && phase != lastPhase) {
                    maxLevel = level
                    lastPhase = phase
                    onPhase(phase)
                }
            }
        }
    }

    internal class RetryableApiException(val code: Int, message: String) : IOException(message)

    internal class ApiConfigException(val code: Int, message: String) : RuntimeException(message)

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
            JSONObject(body).optJSONObject("error")?.optStringOrEmpty("message")
        } catch (e: Exception) {
            null
        }
        val detail = if (!message.isNullOrBlank()) message else body.take(200).trim()
        return if (detail.isBlank()) "HTTP $code" else "HTTP $code: $detail"
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
            List(arr.length()) {
                val v = arr.opt(it)
                if (v == null || v == JSONObject.NULL) "" else v.toString()
            }
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

/**
 * 业务侧统一取翻译器,优先级从高到低:
 *  1. 自定义 AI(用户自己的 OpenAI 兼容接口,配好并启用)
 *  2. PixShaft 云翻译(服务端代理,登录 + 服务端开 + 用户没关)
 *  3. 内置 Google web 端点(国内需代理)
 */
fun currentTranslator(): Translator = when {
    AiTranslator.isActive() -> AiTranslator
    CloudTranslator.isActive() -> CloudTranslator
    else -> GoogleWebTranslator
}
