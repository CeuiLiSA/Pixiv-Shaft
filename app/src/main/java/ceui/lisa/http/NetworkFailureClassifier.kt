package ceui.lisa.http

import ceui.pixiv.nana7mi.session.TransportFailureClassifier
import java.net.SocketTimeoutException

/**
 * Shared transport failure classification backed by `:nana7mi`'s pure-JVM
 * [ceui.pixiv.nana7mi.session.TransportFailureKind] and [TransportFailureClassifier.DEFAULT]
 * so retry policy, Nana7mi telemetry, and GA4 analytics stay on one single source of truth.
 */
internal typealias TransportFailureKind = ceui.pixiv.nana7mi.session.TransportFailureKind

internal fun classifyTransportFailure(error: Throwable): TransportFailureKind? =
    TransportFailureClassifier.DEFAULT.classify(error)

/**
 * 是不是**读超时** —— 对端在链路上沉默了，我们设的读超时到点。
 *
 * 凡是 [SocketTimeoutException]、且不是连接超时，都算。不能只认 message == "timeout"：OkHttp 的
 * HTTP/1 连接上 socket 的 soTimeout 与 okio 的 watchdog 是同一个读超时，谁先到点由谁抛 —— soTimeout
 * 先到时 message 是 "Read timed out"。OkHttp 4.12 本地实测（读 body 中途 / 等响应头各 30 次）
 * 约八成是 "Read timed out"，只有两成是 "timeout"。
 *
 * **连接超时不算**：它的 message 形如 "failed to connect to … after Nms"（Android）/
 * "Connect timed out"（JVM），既不受「图片加载/下载断流阈值」影响，也不该拿「用户调小了阈值」
 * 当理由去静默重连。
 *
 * 刻意声明成 public（同文件其余声明是 internal）：调用方 `Manager` 是 Java 类，internal 的 JVM
 * 可见性名对 Java 侧不友好。
 */
fun isReadTimeoutFailure(error: Throwable): Boolean {
    val causes = generateSequence(error) { current ->
        current.cause?.takeUnless { it === current }
    }
    return causes.any {
        it is SocketTimeoutException && it.message?.contains("connect", ignoreCase = true) != true
    }
}
