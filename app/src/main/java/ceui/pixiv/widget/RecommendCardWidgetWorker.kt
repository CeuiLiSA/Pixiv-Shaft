package ceui.pixiv.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ceui.lisa.R
import ceui.lisa.activities.MainActivity
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.http.Retro
import ceui.loxia.Illust
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.session.SessionManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import timber.log.Timber

class RecommendCardWidgetWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(context)
        val widgetIds = manager.getAppWidgetIds(
            ComponentName(context, RecommendCardWidgetProvider::class.java)
        )
        if (widgetIds.isEmpty()) return Result.success()

        if (SessionManager.getBearerTokenOrEmpty().isEmpty()) {
            widgetIds.forEach {
                renderMessage(manager, it, context.getString(R.string.v3_widget_login_required))
            }
            return Result.success()
        }

        val illusts = try {
            withContext(Dispatchers.IO) {
                Retro.getAppApiSuspend().getRecmdIllust(true)
                    .illusts
                    ?.filter { !it.isR18File() && !it.isSensitive() }
                    ?.shuffled()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        // API 瞬时失败：跳过渲染保留 widget 上已有的画面，交给 retry。
        // 推 renderMessage 会把已显示的图抹成错误卡（与封面加载失败同类问题）
        if (illusts.isNullOrEmpty()) {
            return Result.retry()
        }

        // 老版行为：多个实例各自展示不同的随机作品
        var anyFailed = false
        widgetIds.forEachIndexed { index, widgetId ->
            if (!renderIllust(manager, widgetId, illusts[index % illusts.size])) {
                anyFailed = true
            }
        }
        return if (anyFailed) Result.retry() else Result.success()
    }

    private fun renderMessage(
        manager: AppWidgetManager,
        widgetId: Int,
        message: String,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_v3_recommend_card)
        views.setViewVisibility(R.id.widget_message, android.view.View.VISIBLE)
        views.setTextViewText(R.id.widget_message, message)

        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, widgetId * 37 + 3, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPi)
        applyRefreshButton(views, widgetId)
        views.setViewVisibility(R.id.widget_bookmark, android.view.View.GONE)
        manager.updateAppWidget(widgetId, views)
    }

    /** @return false 表示封面加载失败，本次没有推送任何内容（保留 widget 上已有的画面） */
    private suspend fun renderIllust(
        manager: AppWidgetManager,
        widgetId: Int,
        illust: Illust,
    ): Boolean {
        val opts = manager.getAppWidgetOptions(widgetId)
        val density = context.resources.displayMetrics.density
        val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 150)
        val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 150)
        // Hard pixel cap: 2 MB IPC budget — 600×600 ARGB_8888 ≈ 1.44 MB.
        val coverWidthPx = (widthDp * density).toInt().coerceIn(200, 600)
        val coverHeightPx = (heightDp * density).toInt().coerceIn(200, 600)
        // 整卡就是图片本身，圆角对齐 v3_widget_card_bg 的 28dp
        val coverRadiusPx = (28 * density).toInt()

        val cover = withContext(Dispatchers.IO) {
            try {
                Glide.with(context).asBitmap()
                    .load(GlideUtil.getLargeImage(illust))
                    .apply(
                        RequestOptions().transform(
                            CenterCrop(), RoundedCorners(coverRadiusPx)
                        )
                    )
                    .submit(coverWidthPx, coverHeightPx)
                    .get()
            } catch (e: Exception) {
                Timber.tag("RecommendCardWidget").w(e, "cover load failed, illust=%d", illust.id)
                null
            }
        }

        // 封面失败时直接跳过：推一个没有图的 RemoteViews 会把 widget 上
        // 已经显示的画面抹成空卡（ColorOS 杀进程后台重跑时实测出现过）
        if (cover == null) return false

        val views = RemoteViews(context.packageName, R.layout.widget_v3_recommend_card)
        views.setViewVisibility(R.id.widget_message, android.view.View.GONE)
        views.setImageViewBitmap(R.id.widget_cover, cover)

        val openIntent = Intent(context, VActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Params.POSITION, 0)
            putExtra(Params.PAGE_UUID, UUID.randomUUID().toString())
            // Widget 刷新时不预占进程级 Container；点击后由 VActivity
            // 使用这份 bean 重建单页 PageData。
            putExtra(Params.WIDGET_ILLUST, illust)
        }
        val openPi = PendingIntent.getActivity(
            context, widgetId * 37 + 2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPi)
        applyRefreshButton(views, widgetId)
        if (Shaft.sSettings.isWidgetHideBookmarkButton) {
            views.setViewVisibility(R.id.widget_bookmark, android.view.View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_bookmark, android.view.View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.widget_bookmark,
                WidgetBookmarkReceiver.pendingIntent(context, "card/$widgetId", illust.id.toInt())
            )
        }

        manager.updateAppWidget(widgetId, views)
        return true
    }

    /** 刷新按钮可以被设置隐藏（#1013：挡画面），隐藏时连点击热区一起去掉 */
    private fun applyRefreshButton(views: RemoteViews, widgetId: Int) {
        if (Shaft.sSettings.isWidgetHideRefreshButton) {
            views.setViewVisibility(R.id.widget_refresh, android.view.View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_refresh, android.view.View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent(widgetId))
        }
    }

    /** 右下角刷新：广播 ACTION_APPWIDGET_UPDATE → provider onUpdate → 重新随机一张 */
    private fun refreshPendingIntent(widgetId: Int): PendingIntent {
        val refreshIntent = Intent(context, RecommendCardWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
        }
        return PendingIntent.getBroadcast(
            context, widgetId * 37 + 5, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
