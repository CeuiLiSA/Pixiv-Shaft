package ceui.lisa.http

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * 把「okio/okhttp 内部缓冲被并发写坏」导致的进程崩溃，降级成「这一发请求失败」。
 *
 * 线上崩溃栈：
 * ```
 * Fatal Exception: java.lang.ArrayIndexOutOfBoundsException: size=128 offset=0 byteCount=7724
 *   okio.-SegmentedByteString.checkOffsetAndCount (Util.kt:25)
 *   okio.AsyncTimeout$sink$1.write (AsyncTimeout.kt:109)
 *   okio.RealBufferedSink.emitCompleteSegments (RealBufferedSink.kt:256)
 *   okhttp3.internal.http1.Http1ExchangeCodec.writeRequest (Http1ExchangeCodec.kt:161)
 *   okhttp3.internal.http.CallServerInterceptor.intercept (CallServerInterceptor.kt:41)
 *   ... me.jessyan.progressmanager.ProgressManager$1.intercept ...
 *   okhttp3.internal.connection.RealCall$AsyncCall.run (RealCall.kt:517)
 * ```
 *
 * 怎么读这个栈：`emitCompleteSegments()` 先从 buffer 算出待刷字节数 7724，紧接着调
 * `sink.write(buffer, 7724)` 时 buffer 只剩 128 字节 —— 中间有**另一个线程**把同一个
 * buffer 抽干了。okio 的 Buffer / Segment 链表不是线程安全的，两个线程往同一条连接的
 * sink 里写请求头，size 记账立刻对不上，直接抛 AIOOBE。
 *
 * 也就是说：一条连接被两个 exchange 同时拿到了。早先怀疑是 HTTP/2 的共享帧缓冲（见
 * [ceui.lisa.activities.Shaft] 里强制 HTTP/1.1 的注释），退回 H1.1 后仍然复现 ——
 * 栈里已经是 `Http1ExchangeCodec`，说明成因在连接池的记账，与协议无关。这一层 App 改不动。
 *
 * 但**崩溃形态**是能改的。okhttp 的 `RealCall.AsyncCall.run` 只把 IOException 收进
 * `onFailure`，其余 Throwable 一律 `throw t` —— 从 dispatcher 线程逃逸出去就是 uncaught，
 * 整个进程陪葬。所以这里兜一层：非 IOException 的 RuntimeException 一律包成 IOException。
 *
 * **必须挂 network interceptor**（`addNetworkInterceptor`），不能挂 application 层：
 * 只有在 network 层抛出的 IOException 才会落进 `RetryAndFollowUpInterceptor`，其 finally 里
 * `exitNetworkInterceptorExchange(closeExchange = true)` → `Exchange.detachWithViolence()`
 * 会把这条已经写坏的连接连 socket 一起拆掉，不留在池子里继续害下一发请求；顺带还能在新连接上重试。
 * 挂 application 层则只能拿到「请求失败」，坏连接照样躺在池子里。
 *
 * Error（OOM / StackOverflow 等）不拦：那是真出事了，拦下来只会把问题藏起来。
 */
class BufferCorruptionGuardInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return try {
            chain.proceed(request)
        } catch (e: CancellationException) {
            // 坑：CancellationException 是 IllegalStateException 的子类，同时也是
            // kotlinx.coroutines 的取消信号（typealias）。必须原样穿过去，
            // 否则协程侧会把「取消」误读成「网络错误」。
            throw e
        } catch (e: RuntimeException) {
            // IOException 不是 RuntimeException，正常网络错误走不到这里。
            Timber.e(e, "okhttp internal state corrupted, degraded to IOException: %s", request.url)
            throw IOException("okhttp internal state corrupted for ${request.url}", e)
        }
    }
}
