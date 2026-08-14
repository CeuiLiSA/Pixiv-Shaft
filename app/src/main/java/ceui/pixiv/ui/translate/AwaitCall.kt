package ceui.pixiv.ui.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/**
 * 把 OkHttp 的 [Call] 转成可取消的 suspend 调用,Google/AI 两条翻译链路共用:
 * - 协程取消时调 [Call.cancel] 立刻掐断连接,不再阻塞到 connect/read 超时;
 * - 取消引发的 Canceled 一律以 [CancellationException] 语义返回,绝不让外层把
 *   「取消」当成真实失败弹给用户;
 * - [onResponse] 在 OkHttp 回调线程执行,必须自己消费 body;返回后 [Response] 自动关闭。
 */
internal suspend fun <T> awaitOkHttpCall(
    call: Call,
    onResponse: (Response) -> T,
): T = suspendCancellableCoroutine { cont ->
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) {
                cont.resumeWith(Result.failure(CancellationException("okhttp call cancelled", e)))
            } else {
                cont.resumeWith(Result.failure(e))
            }
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                response.use { resp ->
                    val result = onResponse(resp)
                    if (cont.isCancelled) {
                        cont.resumeWith(Result.failure(CancellationException("okhttp call cancelled")))
                    } else {
                        cont.resumeWith(Result.success(result))
                    }
                }
            } catch (e: Throwable) {
                // 必须兜 Throwable 而不是 Exception:响应体解析可能抛 OutOfMemoryError,
                // 漏掉的话 cont 永远不 resume,协程就无声挂死到页面销毁——正是这里要消灭的问题。
                if (cont.isCancelled) {
                    cont.resumeWith(Result.failure(CancellationException("okhttp call cancelled", e)))
                } else {
                    cont.resumeWith(Result.failure(e))
                }
            }
        }
    })
    cont.invokeOnCancellation { call.cancel() }
}
