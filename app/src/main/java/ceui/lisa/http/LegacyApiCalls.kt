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
import timber.log.Timber

/**
 * 给还是 Java 的 legacy 页面用的网络请求门面：Java 调不了 suspend 函数，这里把
 * [AppApi] / [SignApi] / [ResourceApi] 的调用包成「回调回主线程」的 `@JvmStatic` 方法，
 * 替代原先 `Retro.getAppApi().xxx().subscribeOn(newThread).observeOn(mainThread).subscribe(NullCtrl)`。
 *
 * - 请求挂在 [owner] 的 lifecycleScope 上（Fragment / Activity 自身），页面销毁自动取消；
 *   `owner` 为 null 时挂在 [JavaAsync.appScope]（无宿主的静态任务）。
 * - 失败默认走 [ErrorCtrl.handleError]（与旧 NullCtrl 链路一致的 pixiv 业务错误文案 toast）；
 *   传了 `onError` 则由调用方自己处理。
 * - `onFinally` 对应旧 `NullCtrl.must()`：成功失败都回调，取消不回调。
 *
 * Kotlin 页面不要用这个，直接 `lifecycleScope.launch { Retro.getAppApi().xxx() }`。
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
                onSuccess.accept(result)
                onFinally?.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "LegacyApiCalls request failed")
                if (onError != null) onError.accept(e) else ErrorCtrl.handleError(e)
                onFinally?.run()
            }
        }
    }

    // ── app-api ────────────────────────────────────────────────────────

    @JvmStatic
    fun getAccountState(owner: LifecycleOwner, onSuccess: JavaAsync.Consumer<UserState>) =
        call(owner, onSuccess, null, null) { Retro.getAppApi().getAccountState() }

    @JvmStatic
    fun getPresets(owner: LifecycleOwner, onSuccess: JavaAsync.Consumer<Preset>) =
        call(owner, onSuccess, null, null) { Retro.getAppApi().getPresets() }

    @JvmStatic
    fun getUserDetailV2(owner: LifecycleOwner, userId: Int, onSuccess: JavaAsync.Consumer<UserDetailResponse>) =
        call(owner, onSuccess, null, null) { Retro.getAppApi().getUserDetailV2(userId) }

    @JvmStatic
    fun updateUserProfile(
        owner: LifecycleOwner,
        parts: List<MultipartBody.Part>,
        onSuccess: JavaAsync.Consumer<NullResponse>,
        onError: JavaAsync.Consumer<Throwable>?,
    ) = call(owner, onSuccess, onError, null) { Retro.getAppApi().updateUserProfile(parts) }

    @JvmStatic
    fun getHotTags(owner: LifecycleOwner, type: String, onSuccess: JavaAsync.Consumer<ListTrendingtag>) =
        call(owner, onSuccess, null, null) { Retro.getAppApi().getHotTags(type) }

    @JvmStatic
    fun getNextIllust(
        owner: LifecycleOwner,
        nextUrl: String,
        onSuccess: JavaAsync.Consumer<ListIllust>,
        onFinally: Runnable?,
    ) = call(owner, onSuccess, null, onFinally) { Retro.getAppApi().getNextIllust(nextUrl) }

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
