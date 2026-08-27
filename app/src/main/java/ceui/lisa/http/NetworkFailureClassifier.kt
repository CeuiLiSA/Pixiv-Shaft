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
