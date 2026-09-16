package ceui.pixiv.ui.translate

import ceui.lisa.activities.Shaft
import ceui.pixiv.api.Client
import ceui.pixiv.services.appServices
import ceui.pixiv.session.SessionManager
import ceui.pixiv.shaftapi.PixshaftApi
import ceui.pixiv.shaftapi.ShaftHmac
import ceui.pixiv.shaftapi.TranslateResult
import ceui.pixiv.shaftapi.translateTextsStreaming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * PixShaft 云翻译：文本发给 pixshaft-api，由服务端选择腾讯 Transmart 或 GPT 上游
 * （服务端 `src/translate.js`）。客户端只发 `texts + lang`，引擎与失败回退都在服务端，
 * 额度按源文本字符数扣两只桶（5 小时 + 每周），套餐倍率和热度排序共用。
 *
 * 和 [AiTranslator] 同一套分片/并发/回调纪律：按 [MAX_BATCH_CHARS] 切段、最多
 * [REQUEST_CONCURRENCY] 个分片并发、按序 await 让 onItem/onProgress 保持单协程串行。
 * 没有逐条兜底 —— 服务端已经保证译文与原文等长，对不上直接算失败。
 *
 * 失败以异常上抛，[promptTranslateFailedIfPossible] 按类型分流：[CloudTranslateQuotaException]
 * 弹「额度用完 + 查看用量」，[CloudTranslateException] 弹错误码。
 */
object CloudTranslator : Translator {

    /** 与服务端 TRANSLATE_MAX_CHARS(8000)留足余量；单条超长文本会独占一个分片。 */
    internal const val MAX_BATCH_CHARS = 3000
    /** 服务端 TRANSLATE_MAX_ITEMS：一页漫画气泡再多也不能把 65 条塞进一个请求（会 413）。 */
    internal const val MAX_BATCH_ITEMS = 64
    private const val REQUEST_CONCURRENCY = 4
    private const val TAG = "CloudTranslator"

    private val requestSemaphore = Semaphore(REQUEST_CONCURRENCY)

    /**
     * 能不能走云翻译：用户没关 + 已登录（服务端按 uid 计量）+ 本包带 HMAC（fork 构建签不了名）
     * + 服务端宣告过功能开着。任一不满足就交给下一级翻译器，不报错。
     */
    fun isActive(): Boolean {
        if (Shaft.sSettings?.isCloudTranslateEnabled == false) return false
        if (!ShaftHmac.isConfigured) return false
        if (SessionManager.loggedInUid <= 0L) return false
        val context = Shaft.getContext() ?: return false
        return context.appServices().remoteAppConfig.cloudTranslateEnabled
    }

    override suspend fun translate(
        input: String,
        outputLang: String,
        onPhase: ((AiTranslatePhase) -> Unit)?,
    ): String {
        if (input.isBlank()) return input
        return translateBatch(listOf(input), outputLang, onPhase = onPhase).first().trim()
    }

    override suspend fun translateBatch(
        inputs: List<String>,
        outputLang: String,
        onItem: ((Int, String) -> Unit)?,
        onProgress: ((Int, Int) -> Unit)?,
        onPhase: ((AiTranslatePhase) -> Unit)?,
        onRequestSent: (() -> Unit)?,
    ): List<String> = translateBatchWith(
        Client.pixshaft, SessionManager.loggedInUid, inputs, outputLang,
        onItem, onProgress, onPhase, onRequestSent,
        onServerDisabled = ::markServerDisabled,
        fallback = GoogleWebTranslator,
    )

    /**
     * 服务端回了 `translate_disabled`：运维刚把总开关关了。冷启动那份 `cloudTranslateEnabled`
     * 还是 true，不当场翻掉的话，用户每翻一次都吃一个 503 弹窗直到重启 —— 开关就失去意义了。
     * 翻掉之后下一次翻译由 [currentTranslator] 直接落到 Google，下次冷启动再由服务端决定要不要开回来。
     */
    private fun markServerDisabled() {
        val context = Shaft.getContext() ?: return
        context.appServices().remoteAppConfig.markCloudTranslateDisabled(SessionManager.loggedInUid)
    }

    /** 依赖显式传入的版本，单测用 MockWebServer 起一个 Retrofit 实例直接打。 */
    internal suspend fun translateBatchWith(
        api: PixshaftApi,
        uid: Long,
        inputs: List<String>,
        outputLang: String,
        onItem: ((Int, String) -> Unit)? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
        onPhase: ((AiTranslatePhase) -> Unit)? = null,
        onRequestSent: (() -> Unit)? = null,
        onServerDisabled: (() -> Unit)? = null,
        fallback: Translator? = null,
    ): List<String> {
        if (inputs.isEmpty()) return emptyList()
        // Blank bubbles need no network request. Identical labels are translated
        // once per batch, then restored to every original position for the UI.
        val positions = linkedMapOf<String, MutableList<Int>>()
        inputs.forEachIndexed { index, text ->
            if (text.isNotBlank()) positions.getOrPut(text) { mutableListOf() }.add(index)
        }
        if (positions.isEmpty()) {
            onProgress?.invoke(inputs.size, inputs.size)
            return inputs
        }
        val groups = positions.values.toList()
        val blanks = inputs.size - groups.sumOf { it.size }
        val completedCounts = groups.runningFold(blanks) { count, group -> count + group.size }
        val translated = translateUniqueBatchWith(
            api, uid, positions.keys.toList(), outputLang,
            onItem = { index, text -> groups[index].forEach { onItem?.invoke(it, text) } },
            onProgress = { done, _ -> onProgress?.invoke(completedCounts[done], inputs.size) },
            onPhase, onRequestSent, onServerDisabled, fallback,
        )
        val results = inputs.map { if (it.isBlank()) it else "" }.toMutableList()
        groups.forEachIndexed { index, group -> group.forEach { results[it] = translated[index] } }
        return results
    }

    private suspend fun translateUniqueBatchWith(
        api: PixshaftApi,
        uid: Long,
        inputs: List<String>,
        outputLang: String,
        onItem: ((Int, String) -> Unit)? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
        onPhase: ((AiTranslatePhase) -> Unit)? = null,
        onRequestSent: (() -> Unit)? = null,
        onServerDisabled: (() -> Unit)? = null,
        fallback: Translator? = null,
    ): List<String> = withContext(Dispatchers.IO) {
        if (inputs.isEmpty()) return@withContext emptyList()
        val lang = serverLangOf(outputLang)
        val results = MutableList(inputs.size) { "" }
        val ranges = chunkRanges(inputs, MAX_BATCH_CHARS, MAX_BATCH_ITEMS)
        val phases = AiTranslator.PhaseAggregator(onPhase)
        val lastError = AtomicReference<Exception?>(null)
        // 额度用完 / 功能关闭 / 限流：同一个 uid 的其它分片必然同样失败，别再放出去烧限流额度。
        val stopAll = AtomicReference<Exception?>(null)

        coroutineScope {
            val chunkJobs = ranges.map { (from, to) ->
                val slice = inputs.subList(from, to)
                async {
                    requestSemaphore.withPermit {
                        stopAll.get()?.let { return@withPermit null }
                        // 请求即将发出：业务侧从此刻起要拦退出（服务端已经在替我们烧上游 token）。
                        onRequestSent?.invoke()
                        val chars = slice.sumOf { it.length }
                        Timber.tag(TAG).i(
                            "→ POST /v1/account/translate uid=%d items=%d chars=%d lang=%s chunk=[%d,%d)",
                            uid, slice.size, chars, lang, from, to,
                        )
                        // System.nanoTime 而不是 SystemClock：这段要在 JVM 单测里跑，android.os 没桩。
                        val started = System.nanoTime()
                        val result = api.translateTextsStreaming(uid, slice, lang,
                            onThinking = { phases.report(AiTranslatePhase.Thinking(it)) },
                            onGenerating = { phases.report(AiTranslatePhase.Generating) },
                        )
                        val ms = (System.nanoTime() - started) / 1_000_000
                        when (result) {
                            is TranslateResult.Success -> {
                                Timber.tag(TAG).i("translation engine: %s", result.engine?.display ?: "unspecified")
                                val session = result.quotas.firstOrNull { it.key == "session" }
                                Timber.tag(TAG).i(
                                    "← 200 in %dms items=%d plan=%s session=%s/%s weekly=%s/%s",
                                    ms, result.translations.size, result.plan?.key,
                                    session?.used?.toLong(), session?.max,
                                    result.quotas.firstOrNull { it.key == "weekly" }?.used?.toLong(),
                                    result.quotas.firstOrNull { it.key == "weekly" }?.max,
                                )
                                result.translations
                            }
                            is TranslateResult.RateLimited -> {
                                val e = quotaExceptionOf(result)
                                stopAll.compareAndSet(null, e)
                                lastError.set(e)
                                Timber.tag(TAG).w(e, "← 429 in %dms scope=%s retryAfter=%s chunk=[%d,%d)", ms, result.limit.scope, result.limit.retryAfterSeconds, from, to)
                                null
                            }
                            is TranslateResult.Disabled -> {
                                val e = CloudTranslateException(503, "translate_disabled")
                                stopAll.compareAndSet(null, e)
                                lastError.set(e)
                                Timber.tag(TAG).w("← 503 translate_disabled in %dms: server switched translation off", ms)
                                onServerDisabled?.invoke()
                                null
                            }
                            is TranslateResult.HttpFailure -> {
                                lastError.set(CloudTranslateException(result.status, result.error ?: "HTTP ${result.status}"))
                                Timber.tag(TAG).w("← %d %s in %dms chunk=[%d,%d)", result.status, result.error, ms, from, to)
                                null
                            }
                            is TranslateResult.NetworkFailure -> {
                                lastError.set(result.cause)
                                Timber.tag(TAG).w(result.cause, "← network failure in %dms chunk=[%d,%d)", ms, from, to)
                                null
                            }
                            is TranslateResult.InvalidResponse -> {
                                lastError.set(result.cause ?: IOException("CloudTranslator: malformed response"))
                                Timber.tag(TAG).w(result.cause, "← malformed response in %dms chunk=[%d,%d)", ms, from, to)
                                null
                            }
                        }
                    }
                }
            }

            var done = 0
            for ((i, range) in ranges.withIndex()) {
                coroutineContext.ensureActive()
                val (from, to) = range
                val lines = chunkJobs[i].await()
                if (lines != null) {
                    for (j in lines.indices) {
                        val idx = from + j
                        results[idx] = lines[j]
                        if (lines[j].isNotEmpty()) onItem?.invoke(idx, lines[j])
                    }
                }
                done += (to - from)
                onProgress?.invoke(done, inputs.size)
            }
        }

        // 服务端把功能关了（运维手动开关 / 总开关）：这一次就直接交给下一级引擎重做，用户看到的
        // 只是译文换了个来源，不是一个 503 弹窗；本地开关已在上面翻掉，后续调用不会再到这里。
        val disabled = stopAll.get()
        if (fallback != null && disabled is CloudTranslateException && disabled.code == 503) {
            Timber.tag(TAG).i("server switched off, redoing this batch with %s", fallback::class.simpleName)
            return@withContext fallback.translateBatch(inputs, outputLang, onItem, onProgress, onPhase, onRequestSent)
        }

        if (results.all { it.isBlank() }) {
            throw stopAll.get() ?: lastError.get() ?: IOException("CloudTranslator: all items failed without an exception")
        }
        // 部分分片撞了额度：已拿到的译文照样交出去，但把「为什么少了一截」留在日志里。
        stopAll.get()?.let { Timber.w(it, "CloudTranslator: partial result, %d/%d items", results.count { it.isNotBlank() }, inputs.size) }
        results
    }

    /** 先按字符切（同 [AiTranslator.chunkByCharLimit]），再把每段按条数上限二次切开。 */
    internal fun chunkRanges(inputs: List<String>, maxChars: Int, maxItems: Int): List<Pair<Int, Int>> =
        AiTranslator.chunkByCharLimit(inputs, maxChars).flatMap { (from, to) ->
            (from until to step maxItems).map { start -> start to minOf(start + maxItems, to) }
        }

    /**
     * 服务端只认它自己的白名单（zh-CN / zh-TW / en / ja / ko / ru / tr）。[appTranslateTargetLang]
     * 给的是 gtx 码，`zh` 得映射成 `zh-CN`；其余原样透传，不认的服务端会 400 回来。
     */
    internal fun serverLangOf(lang: String): String = when (lang.lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "zh-CN"
        "zh-tw", "zh-hant" -> "zh-TW"
        else -> lang
    }

    private fun quotaExceptionOf(result: TranslateResult.RateLimited): Exception {
        val limit = result.limit
        if (!limit.isQuota) {
            // 每分钟限流：等一下就好，不是额度问题，按普通失败报。
            return CloudTranslateException(429, "rate_limited:" + (limit.scope ?: "unknown"))
        }
        // resetsAt 是服务端时刻，必须减服务端 serverTime；都缺就退回 Retry-After。
        val resetInMs = when {
            limit.resetsAt != null && limit.serverTime != null -> limit.resetsAt - limit.serverTime
            limit.retryAfterSeconds != null -> limit.retryAfterSeconds * 1000L
            else -> null
        }
        return CloudTranslateQuotaException(limit.scope.orEmpty(), resetInMs?.takeIf { it > 0L })
    }
}

/** 服务端回了非 2xx；[code] 是 HTTP 状态，message 是服务端的 snake_case 错误码。 */
class CloudTranslateException(val code: Int, message: String) : IOException(message)

/** 两只额度桶之一满了。[scope] = `uid_5h` / `uid_weekly`，[resetInMs] 算不出来就是 null。 */
class CloudTranslateQuotaException(val scope: String, val resetInMs: Long?) :
    IOException("cloud translate quota exhausted: $scope")
