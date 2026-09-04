package ceui.lisa.http

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ceui.lisa.core.JavaAsync
import ceui.lisa.model.ListIllust
import ceui.lisa.model.ListTrendingtag
import ceui.lisa.models.AccountEditResponse
import ceui.lisa.models.NullResponse
import ceui.lisa.models.Preset
import ceui.lisa.models.UserDetailResponse
import ceui.lisa.models.UserState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import ceui.pixiv.api.Client
import timber.log.Timber

/**
 * 给还是 Java 的 legacy 页面用的网络请求门面：Java 调不了 suspend 函数，这里把
 * app-api / [SignApi] / [ResourceApi] 的调用包成「回调回主线程」的 `@JvmStatic` 方法，
 * 替代原先的 RxJava 请求链。
 *
 * - 请求挂在 [owner] 的 lifecycleScope 上（Fragment / Activity 自身），页面销毁自动取消；
 *   `owner` 为 null 时挂在 [JavaAsync.appScope]（无宿主的静态任务）。
 * - 失败默认走 [ErrorCtrl.handleError]（与旧 NullCtrl 链路一致的 pixiv 业务错误文案 toast）；
 *   传了 `onError` 则由调用方自己处理。
 * - `onFinally` 对应旧 `doFinally`/`NullCtrl.must()`：成功、失败、**取消**都恰好回调一次——
 *   VActivity 翻页的闸门放在跨配置变更存活的 PageData 上，取消不放闸会永久卡死翻页。
 * - `onSuccess` 自己抛的异常只记日志，不当成请求失败去弹「网络错误」。
 *
 * Kotlin 页面不要用这个，直接 `lifecycleScope.launch { Client.appApi.xxx() }`。
 */
object LegacyApiCalls {

    private fun <T> call(
        owner: LifecycleOwner?,
        onSuccess: JavaAsync.Consumer<T>,
        onError: JavaAsync.Consumer<Throwable>?,
        onFinally: Runnable?,
        request: suspend () -> T,
    ) {
        val scope = owner?.lifecycleScope ?: JavaAsync.appScope
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { request() }
                try {
                    onSuccess.accept(result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Timber.e(e, "LegacyApiCalls onSuccess threw")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "LegacyApiCalls request failed")
                if (onError != null) onError.accept(e) else ErrorCtrl.handleError(e)
            } finally {
                onFinally?.run()
            }
        }
    }

    // ── app-api ────────────────────────────────────────────────────────

    @JvmStatic
    fun getAccountState(owner: LifecycleOwner, onSuccess: JavaAsync.Consumer<UserState>) =
        call(owner, onSuccess, null, null) { Client.appApi.getAccountState() }

    @JvmStatic
    fun getPresets(owner: LifecycleOwner, onSuccess: JavaAsync.Consumer<Preset>) =
        call(owner, onSuccess, null, null) { Client.appApi.getPresets() }

    @JvmStatic
    fun getUserDetailV2(owner: LifecycleOwner, userId: Long, onSuccess: JavaAsync.Consumer<UserDetailResponse>) =
        call(owner, onSuccess, null, null) { Client.appApi.getUserDetailV2(userId) }

    @JvmStatic
    fun updateUserProfile(
        owner: LifecycleOwner,
        parts: List<MultipartBody.Part>,
        onSuccess: JavaAsync.Consumer<NullResponse>,
        onError: JavaAsync.Consumer<Throwable>?,
    ) = call(owner, onSuccess, onError, null) { Client.appApi.updateUserProfile(parts) }

    @JvmStatic
    fun getHotTags(owner: LifecycleOwner, type: String, onSuccess: JavaAsync.Consumer<ListTrendingtag>) =
        call(owner, onSuccess, null, null) { Client.appApi.getHotTags(type) }

    @JvmStatic
    fun getNextIllust(
        owner: LifecycleOwner,
        nextUrl: String,
        onSuccess: JavaAsync.Consumer<ListIllust>,
        onFinally: Runnable?,
    ) = call(owner, onSuccess, null, onFinally) { Client.appApi.getNextIllust(nextUrl) }

    // ── accounts.pixiv.net ─────────────────────────────────────────────

    /** 改账号资料：只传要改的项（null = 不改），current_password 必填。 */
    @JvmStatic
    fun editAccount(
        owner: LifecycleOwner,
        token: String,
        newMailAddress: String?,
        newUserAccount: String?,
        currentPassword: String,
        newPassword: String?,
        onSuccess: JavaAsync.Consumer<AccountEditResponse>,
    ) = call(owner, onSuccess, null, null) {
        Retro.getSignApi().edit(token, newMailAddress, newUserAccount, currentPassword, newPassword)
    }

    // ── jsDelivr 静态资源 ──────────────────────────────────────────────

    /** 读 jsDelivr 上的文本资源；`body.string()` 在 IO 线程读完再回主线程。path 为 null 时取评论过滤规则。 */
    @JvmStatic
    fun getResourceText(
        owner: LifecycleOwner?,
        path: String?,
        onSuccess: JavaAsync.Consumer<String>,
        onError: JavaAsync.Consumer<Throwable>?,
    ) = call(owner, onSuccess, onError, null) {
        val api = Retro.getResourceApi()
        (if (path == null) api.getCommentFilterRule() else api.getByPath(path)).string()
    }
}
