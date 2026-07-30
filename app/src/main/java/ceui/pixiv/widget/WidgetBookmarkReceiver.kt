package ceui.pixiv.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * 桌面小组件上的收藏按钮（issue #641）。点击 → 广播到这里 → 交给
 * [WidgetBookmarkWorker] 在后台发收藏请求；receiver 本身不碰网络。
 */
class WidgetBookmarkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val illustId = intent.getIntExtra(EXTRA_ILLUST_ID, 0)
        if (illustId <= 0) return
        val request = OneTimeWorkRequestBuilder<WidgetBookmarkWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(EXTRA_ILLUST_ID to illustId))
            .build()
        // 按作品去重：同一张图连点多下只收藏一次
        WorkManager.getInstance(context).enqueueUniqueWork(
            "v3_widget_bookmark_$illustId",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        const val EXTRA_ILLUST_ID = "widget_bookmark_illust_id"

        /**
         * @param slot 每个 widget 实例（乃至实例里的每个格子）一个独立 slot，
         *             作为 data URI 区分 PendingIntent —— 只靠 requestCode 会在
         *             不同 widget 家族之间撞车，导致 A 组件收藏了 B 组件的图。
         */
        fun pendingIntent(context: Context, slot: String, illustId: Int): PendingIntent {
            val intent = Intent(context, WidgetBookmarkReceiver::class.java).apply {
                data = Uri.parse("pixivshaft://widget-bookmark/$slot")
                putExtra(EXTRA_ILLUST_ID, illustId)
            }
            return PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
