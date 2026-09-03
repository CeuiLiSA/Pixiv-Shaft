package ceui.pixiv.ui.translate

import ceui.lisa.activities.Shaft
import ceui.pixiv.api.Client
import ceui.pixiv.services.appServices
import ceui.pixiv.session.SessionManager
import ceui.pixiv.shaftapi.PixshaftApi
import ceui.pixiv.shaftapi.ShaftHmac
import ceui.pixiv.shaftapi.TranslateResult
import ceui.pixiv.shaftapi.translateTexts
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
 * PixShaft 云翻译：文本发给 pixshaft-api，由服务端转给它自己配的 OpenAI 兼容上游
 * （服务端 `src/translate.js`）。客户端只发 `texts + lang`，模型/提示词/思考参数都在服务端，
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
    private const val REQUEST_CONCURRENCY = 4

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
    ): List<String> = translateBatchWith(Client.pixshaft, SessionManager.loggedInUid, inputs, outputLang, onItem, onProgress, onPhase, onRequestSent)

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
    ): List<String> = withContext(Dispatchers.IO) {
        if (inputs.isEmpty()) return@withContext emptyList()
        val lang = serverLangOf(outputLang)
        val results = MutableList(inputs.size) { "" }
        val ranges = AiTranslator.chunkByCharLimit(inputs, MAX_BATCH_CHARS)
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
                        onPhase?.invoke(AiTranslatePhase.GENERATING)
                        when (val result = api.translateTexts(uid, slice, lang)) {
                            is TranslateResult.Success -> result.translations
                            is TranslateResult.RateLimited -> {
                                val e = quotaExceptionOf(result)
                                stopAll.compareAndSet(null, e)
                                lastError.set(e)
                                Timber.w(e, "CloudTranslator: batch [%d,%d) rate limited", from, to)
                                null
                            }
                            is TranslateResult.Disabled -> {
                                val e = CloudTranslateException(503, "translate_disabled")
                                stopAll.compareAndSet(null, e)
                                lastError.set(e)
                                Timber.w("CloudTranslator: server switched translation off")
                                null
                            }
                            is TranslateResult.HttpFailure -> {
                                lastError.set(CloudTranslateException(result.status, result.error ?: "HTTP ${result.status}"))
                                Timber.w("CloudTranslator: batch [%d,%d) HTTP %d %s", from, to, result.status, result.error)
                                null
                            }
                            is TranslateResult.NetworkFailure -> {
                                lastError.set(result.cause)
                                Timber.w(result.cause, "CloudTranslator: batch [%d,%d) network failure", from, to)
                                null
                            }
                            is TranslateResult.InvalidResponse -> {
                                lastError.set(result.cause ?: IOException("CloudTranslator: malformed response"))
                                Timber.w(result.cause, "CloudTranslator: batch [%d,%d) malformed response", from, to)
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

        if (results.all { it.isBlank() }) {
            throw stopAll.get() ?: lastError.get() ?: IOException("CloudTranslator: all items failed without an exception")
        }
        // 部分分片撞了额度：已拿到的译文照样交出去，但把「为什么少了一截」留在日志里。
        stopAll.get()?.let { Timber.w(it, "CloudTranslator: partial result, %d/%d items", results.count { it.isNotBlank() }, inputs.size) }
        results
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
