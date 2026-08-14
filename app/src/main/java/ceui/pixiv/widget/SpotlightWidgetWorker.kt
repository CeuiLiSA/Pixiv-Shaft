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
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.pixiv.session.SessionManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID

class SpotlightWidgetWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(context)
        val widgetIds = manager.getAppWidgetIds(
            ComponentName(context, SpotlightWidgetProvider::class.java)
        )
        if (widgetIds.isEmpty()) return Result.success()

        if (SessionManager.getBearerTokenOrEmpty().isEmpty()) {
            widgetIds.forEach {
                renderEmpty(manager, it, context.getString(R.string.v3_widget_login_required))
            }
            return Result.success()
        }

        val illust = try {
            withContext(Dispatchers.IO) {
                Retro.getAppApi().getRecmdIllust(true)
                    .blockingFirst()
                    ?.illusts
                    ?.shuffled()
                    ?.firstOrNull { !it.isR18File && !it.isSensitive }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        // API 瞬时失败：跳过渲染保留 widget 上已有的画面，交给 retry。
        // 推 renderEmpty 会把已显示的图抹成错误卡（与封面加载失败同类问题）
        if (illust == null) {
            return Result.retry()
        }

        var anyFailed = false
        for (widgetId in widgetIds) {
            if (!renderIllust(manager, widgetId, illust)) {
                anyFailed = true
            }
        }
        return if (anyFailed) Result.retry() else Result.success()
    }

    private fun renderEmpty(
        manager: AppWidgetManager,
        widgetId: Int,
        message: String,
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_v3_spotlight)
        views.setTextViewText(R.id.widget_title, message)
        views.setTextViewText(R.id.widget_author_name, "")
        views.setTextViewText(R.id.widget_bookmark_count, "—")
        views.setTextViewText(R.id.widget_view_count, "—")

        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, widgetId * 31 + 3, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPi)
        views.setViewVisibility(R.id.widget_bookmark, android.view.View.GONE)
        manager.updateAppWidget(widgetId, views)
    }

    /** @return false 表示封面加载失败，本次没有推送任何内容（保留 widget 上已有的画面） */
    private suspend fun renderIllust(
        manager: AppWidgetManager,
        widgetId: Int,
        illust: IllustsBean,
    ): Boolean {
        val opts = manager.getAppWidgetOptions(widgetId)
        val density = context.resources.displayMetrics.density
        val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 280)
        val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 120)
        val widthPx = (widthDp * density).toInt().coerceAtLeast((220 * density).toInt())
        val heightPx = (heightDp * density).toInt().coerceAtLeast((100 * density).toInt())

        // ImageView size: card padding 8dp × 2 + 10dp gap between cover and column = 26dp.
        // Cover takes 62/100 weight. Match the bitmap to that exact size so fitXY
        // doesn't stretch (and the pre-baked rounded corners survive).
        // Hard pixel cap: 2 MB IPC budget — 600×600 ARGB_8888 ≈ 1.44 MB.
        val coverWidthPx = ((widthPx - 26 * density) * 0.62f).toInt()
            .coerceIn(200, 600)
        val coverHeightPx = (heightPx - 16 * density).toInt()
            .coerceIn(200, 600)
        val coverRadiusPx = (20 * density).toInt()
        val avatarPx = (20 * density).toInt().coerceAtMost(72)

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
                e.printStackTrace(); null
            }
        }
        // 封面失败时直接跳过：推一个没有图的 RemoteViews 会把 widget 上
        // 已经显示的画面抹成空卡（ColorOS 杀进程后台重跑时实测出现过）
        if (cover == null) return false

        val avatarGlideUrl = illust.user?.let { GlideUtil.getHead(it) }
        val avatar = if (avatarGlideUrl != null) withContext(Dispatchers.IO) {
            try {
                Glide.with(context).asBitmap()
                    .load(avatarGlideUrl)
                    .apply(RequestOptions().transform(CircleCrop()))
                    .submit(avatarPx, avatarPx)
                    .get()
            } catch (e: Exception) {
                e.printStackTrace(); null
            }
        } else null

        val views = RemoteViews(context.packageName, R.layout.widget_v3_spotlight)
        views.setTextViewText(R.id.widget_title, illust.title.orEmpty())
        views.setTextViewText(R.id.widget_author_name, illust.user?.name.orEmpty())
        views.setTextViewText(R.id.widget_bookmark_count, formatCount(illust.total_bookmarks ?: 0))
        views.setTextViewText(R.id.widget_view_count, formatCount(illust.total_view ?: 0))
        views.setImageViewBitmap(R.id.widget_cover, cover)
        avatar?.let { views.setImageViewBitmap(R.id.widget_author_avatar, it) }

        val openIntent = Intent(context, VActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Params.POSITION, 0)
            putExtra(Params.PAGE_UUID, UUID.randomUUID().toString())
            // Widget 刷新时不预占进程级 Container；点击后由 VActivity
            // 使用这份 bean 重建单页 PageData。
            putExtra(Params.WIDGET_ILLUST, illust)
        }
        val openPi = PendingIntent.getActivity(
            context, widgetId * 31 + 2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPi)
        if (Shaft.sSettings.isWidgetHideBookmarkButton) {
            views.setViewVisibility(R.id.widget_bookmark, android.view.View.GONE)
        } else {
            views.setViewVisibility(R.id.widget_bookmark, android.view.View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.widget_bookmark,
                WidgetBookmarkReceiver.pendingIntent(context, "spotlight/$widgetId", illust.id)
            )
        }

        manager.updateAppWidget(widgetId, views)
        return true
    }

    private fun formatCount(value: Int): String {
        return when {
            value < 1000 -> value.toString()
            value < 10_000 -> String.format(Locale.US, "%.1fk", value / 1000f)
            value < 1_000_000 -> "${value / 1000}k"
            else -> String.format(Locale.US, "%.1fM", value / 1_000_000f)
        }
    }
}
