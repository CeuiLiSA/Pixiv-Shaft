package ceui.lisa.core

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ceui.lisa.http.ErrorCtrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.Callable

/**
 * 给还是 Java 的 legacy 页面用的协程桥：替代原先 `RxRun.runOn` / `Observable.subscribeOn(io)
 * .observeOn(mainThread).subscribe(NullCtrl)` 那一套。
 *
 * - [run]：`work` 在 IO 跑，`onSuccess` / `onError` 回主线程；挂在 [LifecycleOwner]（Fragment 传
 *   `getViewLifecycleOwner()`，Activity 传 `this`）的 lifecycleScope 上，页面销毁自动取消。
 * - `onError` 传 null 时默认走 [ErrorCtrl.handleError]（解析 pixiv 业务错误文案弹 toast），
 *   和 legacy `NullCtrl` 链路的提示一致。
 * - [fireAndForget]：无宿主的一次性后台任务（写库、打点），跑在应用级 scope 上。
 *
 * Java 调不了 suspend 函数：网络请求这类需要 [ceui.lisa.http.AppApi] 的逻辑，
 * 放进 Kotlin 侧的 `@JvmStatic` 回调式门面（如 PixivOperate），Java 只管传回调；
 * 这里的 [run] 只适合纯阻塞工作（文件 / 数据库 / 解压）。
 *
 * Kotlin 代码不要用这个 —— 直接 `launchSuspend { }` + suspend API。
 */
object JavaAsync {

    /** 应用级后台 scope：SupervisorJob，单个任务失败不连坐。 */
    @JvmStatic
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun interface Consumer<T> {
        fun accept(value: T)
    }

    @JvmStatic
    @JvmOverloads
    fun <T> run(
        owner: LifecycleOwner,
        work: Callable<T>,
        onSuccess: Consumer<T>,
        onError: Consumer<Throwable>? = null,
        onFinally: Runnable? = null,
    ) {
        owner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { work.call() }
                onSuccess.accept(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "JavaAsync.run failed")
                if (onError != null) onError.accept(e) else ErrorCtrl.handleError(e)
            } finally {
                onFinally?.run()
            }
        }
    }

    /** 无宿主后台任务：失败只打日志（或交给 [onError]），不弹 toast。 */
    @JvmStatic
    @JvmOverloads
    fun fireAndForget(work: Runnable, onError: Consumer<Throwable>? = null) {
        appScope.launch(Dispatchers.IO) {
            try {
                work.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "JavaAsync.fireAndForget failed")
                onError?.accept(e)
            }
        }
    }
}
