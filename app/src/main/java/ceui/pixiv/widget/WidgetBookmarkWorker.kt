package ceui.pixiv.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.http.Retro
import ceui.lisa.utils.Common
import ceui.lisa.utils.Params
import ceui.pixiv.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 执行小组件发起的收藏。restrict 跟随应用内「默认私密收藏」设置，
 * 成功/失败都 toast（Toaster 线程安全，worker 线程可直接调）。
 */
class WidgetBookmarkWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val illustId = inputData.getInt(WidgetBookmarkReceiver.EXTRA_ILLUST_ID, 0)
        if (illustId <= 0) return Result.success()

        if (SessionManager.getBearerTokenOrEmpty().isEmpty()) {
            Common.showToast(context.getString(R.string.v3_widget_login_required))
            return Result.success()
        }

        val starType = if (Shaft.sSettings.isPrivateStar) Params.TYPE_PRIVATE else Params.TYPE_PUBLIC
        return try {
            withContext(Dispatchers.IO) {
                Retro.getAppApiSuspend().postLikeIllust(illustId, starType)
            }
            Common.showToast(
                context.getString(
                    if (starType == Params.TYPE_PUBLIC) R.string.like_novel_success_public
                    else R.string.like_novel_success_private
                )
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("WidgetBookmark").w(e, "bookmark failed, illust=%d", illustId)
            Common.showToast(context.getString(R.string.v3_widget_bookmark_failed))
            // 不静默 retry：失败已经 toast 过，用户想收藏可以再点一下
            Result.success()
        }
    }
}
