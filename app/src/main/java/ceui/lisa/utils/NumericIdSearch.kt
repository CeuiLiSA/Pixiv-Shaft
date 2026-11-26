package ceui.lisa.utils

import android.app.Activity
import android.content.Intent
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.activities.UActivity
import ceui.lisa.http.ErrorCtrl
import ceui.pixiv.utils.fetchFullIllustDetail
import ceui.pixiv.witstudio.dialog.WitTipDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 自动识别数字 ID。先查作品（含网页兜底），查不到时打开用户页。 */
@MainThread
internal fun searchNumericId(
    activity: Activity,
    owner: LifecycleOwner,
    keyword: String,
    onOpened: Runnable?,
) {
    owner.lifecycleScope.launch {
        if (activity.isFinishing || activity.isDestroyed) return@launch

        val dialog = WitTipDialog.Builder(activity)
            .setTipWord(activity.getString(R.string.string_429))
            .create()
        val request = coroutineContext.job
        dialog.setOnCancelListener { request.cancel() }
        dialog.show()

        val id = Common.safeUserId(keyword)
        val (illust, transportError) = try {
            val result = withContext(Dispatchers.IO) {
                var error: Throwable? = null
                val illust = fetchFullIllustDetail(id, onTransportError = { error = it })
                illust to error
            }
            if (activity.isFinishing || activity.isDestroyed || !dialog.hasWindow() ||
                !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                return@launch
            }
            result
        } finally {
            if (dialog.hasWindow()) dialog.dismiss()
        }

        if (illust != null) {
            PixivOperate.openIllustDetail(activity, illust)
            PixivOperate.insertSearchHistory(keyword, SearchTypeUtil.SEARCH_TYPE_DB_ILLUSTSID)
        } else {
            if (transportError != null) ErrorCtrl.handleError(transportError) else Common.showToast(R.string.string_206)
            PixivOperate.insertSearchHistory(keyword, SearchTypeUtil.SEARCH_TYPE_DB_USERID)
            activity.startActivity(Intent(activity, UActivity::class.java).putExtra(Params.USER_ID, id))
        }
        onOpened?.run()
    }
}

// DecorView 从加入 WindowManager 到移除期间有 parent，首帧绘制前也能安全关闭。
private fun WitTipDialog.hasWindow(): Boolean = isShowing && window?.peekDecorView()?.parent != null
