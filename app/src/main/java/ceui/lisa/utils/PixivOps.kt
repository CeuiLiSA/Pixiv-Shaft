package ceui.lisa.utils

import ceui.lisa.core.JavaAsync
import ceui.lisa.http.ErrorCtrl
import ceui.lisa.models.GifResponse
import ceui.lisa.models.IllustSearchResponse
import ceui.lisa.models.NovelSearchResponse
import ceui.lisa.models.NullResponse
import ceui.lisa.model.ListIllust
import ceui.pixiv.api.API
import ceui.pixiv.api.Client
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * [PixivOperate]（Java）打 app-api 的协程门面：Java 调不了 suspend，这里把每个端点包成
 * 「后台请求 + 主线程回调」的 `@JvmStatic` 方法，替代原先 `subscribeOn(newThread)
 * .observeOn(mainThread).subscribe(NullCtrl/ErrorCtrl)` 那一套。
 *
 * - 回调一律回主线程（对齐 Rx `observeOn(mainThread)` 永远 post 的语义，用 [Dispatchers.Main]
 *   而不是 immediate）。
 * - `onError` 传 null 时走 [ErrorCtrl.handleError]（解析 pixiv 业务错误文案弹 toast），
 *   与 legacy `NullCtrl` / `ErrorCtrl` 链路的提示一致；`onFinally` 对应 `NullCtrl.must`，
 *   成功失败都走。
 * - 请求挂在 [JavaAsync.appScope] 上（不随页面销毁取消）：这些操作原本也不随页面取消，
 *   而且成功回调里有落库 / 广播这类「发出去就该做完」的事。
 *
 * Kotlin 新代码不要用这里 —— 直接 `launchSuspend { Client.appApi.xxx() }`。
 */
object PixivOps {

    private fun <T> request(
        onSuccess: JavaAsync.Consumer<T>,
        onError: JavaAsync.Consumer<Throwable>?,
        onFinally: Runnable?,
        call: suspend API.() -> T,
    ) {
        JavaAsync.appScope.launch(Dispatchers.Main) {
            try {
                val result = withContext(Dispatchers.IO) { Client.appApi.call() }
                onSuccess.accept(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "PixivOps request failed")
                if (onError != null) onError.accept(e) else ErrorCtrl.handleError(e)
            } finally {
                onFinally?.run()
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun getIllustByID(
        illustId: Long,
        onSuccess: JavaAsync.Consumer<IllustSearchResponse>,
        onError: JavaAsync.Consumer<Throwable>? = null,
        onFinally: Runnable? = null,
    ) = request(onSuccess, onError, onFinally) { getIllustByID(illustId) }

    @JvmStatic
    @JvmOverloads
    fun getNovelByID(
        novelId: Long,
        onSuccess: JavaAsync.Consumer<NovelSearchResponse>,
        onError: JavaAsync.Consumer<Throwable>? = null,
        onFinally: Runnable? = null,
    ) = request(onSuccess, onError, onFinally) { getNovelByID(novelId) }

    @JvmStatic
    @JvmOverloads
    fun getGifPackage(
        illustId: Long,
        onSuccess: JavaAsync.Consumer<GifResponse>,
        onError: JavaAsync.Consumer<Throwable>? = null,
    ) = request(onSuccess, onError, null) { getGifPackage(illustId) }

    @JvmStatic
    fun relatedIllust(
        illustId: Long,
        onSuccess: JavaAsync.Consumer<ListIllust>,
    ) = request(onSuccess, null, null) { relatedIllust(illustId) }

    @JvmStatic
    fun postAddNovelMarker(
        novelId: Int,
        page: Int,
        onSuccess: JavaAsync.Consumer<NullResponse>,
    ) = request(onSuccess, null, null) { postAddNovelMarker(novelId, page) }

    @JvmStatic
    fun postDeleteNovelMarker(
        novelId: Int,
        onSuccess: JavaAsync.Consumer<NullResponse>,
    ) = request(onSuccess, null, null) { postDeleteNovelMarker(novelId) }
}
