package ceui.lisa.http

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/** Stable, unobfuscated transport buckets shared by retry policy and telemetry. */
internal enum class TransportFailureKind(
    val wire: String,
    val retryable: Boolean,
) {
    CANCELLED("network_cancelled", false),
    TIMEOUT("network_timeout", true),
    DNS("network_dns", true),
    TLS("network_tls", false),
    CONNECT("network_connect", true),
    SOCKET("network_socket", true),
    CRONET("network_cronet", true),
    IO("network_io", true),
}

/**
 * Looks through wrappers because Retrofit, OkHttp and Cronet each add a layer in different paths.
 * The order is intentional: SocketTimeoutException is also an InterruptedIOException, and specific
 * socket/DNS/TLS failures are also IOExceptions.
 */
internal fun classifyTransportFailure(error: Throwable): TransportFailureKind? {
    val causes = generateSequence(error) { current ->
        current.cause?.takeUnless { it === current }
    }.toList()
    return when {
        causes.any { it is CancellationException } -> TransportFailureKind.CANCELLED
        causes.any { it is SocketTimeoutException } -> TransportFailureKind.TIMEOUT
        causes.any { it is UnknownHostException } -> TransportFailureKind.DNS
        causes.any { it is SSLException } -> TransportFailureKind.TLS
        causes.any { it is ConnectException || it is NoRouteToHostException } ->
            TransportFailureKind.CONNECT
        causes.any { it is SocketException } -> TransportFailureKind.SOCKET
        causes.any { it.javaClass.name.startsWith("org.chromium.net.") } ->
            TransportFailureKind.CRONET
        causes.any { it is InterruptedIOException } -> TransportFailureKind.CANCELLED
        causes.any { it is IOException } -> TransportFailureKind.IO
        else -> null
    }
}

/** OkHttp 读 / 调用超时的固定 message（`RealCall.AsyncTimeout.newTimeoutException` 里写死 "timeout"）。 */
private const val OKHTTP_READ_TIMEOUT_MESSAGE = "timeout"

/**
 * 是不是**读超时** —— 对端在链路上沉默了，我们设的读超时到点。
 *
 * 只看 [SocketTimeoutException] 且 message 恰为 "timeout"：OkHttp 的读 / 调用超时统一抛这个。
 * **连接超时不算** —— 它的 message 形如 "failed to connect to … after Nms"，既不受「图片加载/下载
 * 断流阈值」影响，也不该拿「用户调小了阈值」当理由去静默重连。
 *
 * 刻意声明成 public（同文件其余声明是 internal）：调用方 `Manager` 是 Java 类，internal 的 JVM
 * 可见性名对 Java 侧不友好。
 */
fun isReadTimeoutFailure(error: Throwable): Boolean {
    val causes = generateSequence(error) { current ->
        current.cause?.takeUnless { it === current }
    }
    return causes.any { it is SocketTimeoutException && it.message == OKHTTP_READ_TIMEOUT_MESSAGE }
}
