package ceui.pixiv.analytics

import ceui.lisa.http.classifyTransportFailure
import ceui.pixiv.download.StorageSpaceGuard
import ceui.pixiv.login.PixivOAuthResult
import com.google.gson.JsonParseException
import com.google.gson.stream.MalformedJsonException
import retrofit2.HttpException

/**
 * 把运行时对象归到埋点的有限取值上。纯函数，单测见 `AnalyticsClassifiersTest`。
 *
 * 失败原因与 [ceui.pixiv.actions.Nana7miSearchTelemetry] 用同一套词：`http_<状态码>`、`decode_json`、
 * [classifyTransportFailure] 的 `network_*`、兜底 `unexpected`——服务端的搜索报表和 GA4 能直接对照。
 * 都沿 cause 链找：Retrofit / OkHttp / Cronet 各包一层，只看最外层会把 DNS 失败认成普通 IO。
 *
 * 只按**类型**和状态码分档，不读响应正文、不拿异常 message 当值：正文是一次性的
 * （`getHumanReadableMessage` 还要读它判 Cloudflare 拦截），message 则是无限基数。
 */
internal object AnalyticsClassifiers {

    const val UNEXPECTED = "unexpected"
    const val STORAGE_UNAVAILABLE = "storage_unavailable"

    /** 下载管线自己抛的 `IOException("HTTP 403")`：状态码只能从 message 前缀读，且只取这三位数字。 */
    private val HTTP_STATUS_IN_MESSAGE = Regex("""^HTTP (\d{3})\b""")

    /** pixiv 的 `Illust.type`：漫画和动图在 API 里也是 illust，只是 type 不同。 */
    fun contentTypeOf(illustType: String?): Analytics.ContentType = when (illustType) {
        "manga" -> Analytics.ContentType.MANGA
        "ugoira" -> Analytics.ContentType.UGOIRA
        else -> Analytics.ContentType.ILLUST
    }

    fun failureType(error: Throwable): String {
        val causes = causeChain(error)
        causes.firstNotNullOfOrNull { (it as? HttpException)?.code() }?.let { return "http_$it" }
        if (causes.any { it is JsonParseException || it is MalformedJsonException }) return "decode_json"
        return classifyTransportFailure(error)?.wire ?: UNEXPECTED
    }

    /** 单页下载失败：存储问题优先（它们也是 IOException），再看 HTTP 状态，最后落到传输层分档。 */
    fun downloadFailureReason(error: Throwable?): String {
        if (error == null) return UNEXPECTED
        if (StorageSpaceGuard.isOutOfSpace(error)) return "storage_full"
        if (causeChain(error).any { it is SecurityException }) return "storage_permission"
        HTTP_STATUS_IN_MESSAGE.find(error.message.orEmpty())?.let { return "http_${it.groupValues[1]}" }
        return failureType(error)
    }

    fun loginFailureReason(failure: PixivOAuthResult.Failure): String = when (failure) {
        is PixivOAuthResult.Failure.NetworkError -> failure.cause?.let(::failureType) ?: "network_io"
        is PixivOAuthResult.Failure.ServerRejected -> "http_${failure.httpCode}"
        is PixivOAuthResult.Failure.MissingVerifier -> "missing_verifier"
        is PixivOAuthResult.Failure.MissingCode -> "missing_code"
        else -> UNEXPECTED
    }

    private fun causeChain(error: Throwable): List<Throwable> =
        generateSequence(error) { current -> current.cause?.takeUnless { it === current } }
            .take(MAX_CAUSE_DEPTH)
            .toList()

    private const val MAX_CAUSE_DEPTH = 8
}
