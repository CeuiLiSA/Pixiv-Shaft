package ceui.pixiv.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ceui.lisa.R
import ceui.lisa.activities.MainActivity
import ceui.lisa.activities.VActivity
import ceui.lisa.models.IllustsBean
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

/**
 * Renders three illust thumbnails in a horizontal strip. Subclasses pick the
 * data source (daily ranking, recommendations, …); the layout, sizing, and
 * RemoteViews IPC handling live here.
 */
abstract class BaseStripWidgetWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    protected abstract val providerClass: Class<out AppWidgetProvider>

    /** Fetch up to three illusts. Return null to signal transient failure (worker will retry). */
    protected abstract suspend fun fetchIllusts(): List<IllustsBean>?

    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(context)
        val widgetIds = manager.getAppWidgetIds(ComponentName(context, providerClass))
        if (widgetIds.isEmpty()) return Result.success()

        if (SessionManager.getBearerTokenOrEmpty().isEmpty()) {
            widgetIds.forEach { renderPlaceholder(manager, it) }
            return Result.success()
        }

        val illusts = try {
            fetchIllusts()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
        // API 瞬时失败：跳过渲染保留 widget 上已有的画面，交给 retry。
        // 推 renderPlaceholder 会把已显示的三张图抹成空白占位格（与封面加载失败同类问题）
        if (illusts.isNullOrEmpty()) {
            return Result.retry()
        }

        var anyFailed = false
        for (widgetId in widgetIds) {
            if (!renderStrip(manager, widgetId, illusts.take(3))) {
                anyFailed = true
            }
        }
        return if (anyFailed) Result.retry() else Result.success()
    }

    private fun renderPlaceholder(manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_v3_strip)
        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, widgetId * 41 + 7, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPi)
        manager.updateAppWidget(widgetId, views)
    }

    /** @return false 表示封面全部加载失败，本次没有推送任何内容（保留 widget 上已有的画面） */
    private suspend fun renderStrip(
        manager: AppWidgetManager,
        widgetId: Int,
        illusts: List<IllustsBean>,
    ): Boolean {
        val opts = manager.getAppWidgetOptions(widgetId)
        val density = context.resources.displayMetrics.density
        val widthDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320)
        val heightDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 130)
        val widthPx = (widthDp * density).toInt().coerceAtLeast((250 * density).toInt())
        val heightPx = (heightDp * density).toInt().coerceAtLeast((100 * density).toInt())

        // Strip layout: 12dp padding × 2 + 2dp gap × 2 = 28dp. Divide rest by 3.
        // Hard pixel cap so the three bitmaps together don't blow the 2 MB IPC.
        val coverWidthPx = ((widthPx - 28 * density) / 3f).toInt()
            .coerceIn(120, 360)
        val coverHeightPx = (heightPx - 24 * density).toInt()
            .coerceIn(120, 360)
        val coverRadiusPx = (16 * density).toInt()

        val views = RemoteViews(context.packageName, R.layout.widget_v3_strip)
        val slots = listOf(R.id.widget_cover_0, R.id.widget_cover_1, R.id.widget_cover_2)
        var loadedCount = 0

        for (index in slots.indices) {
            val viewId = slots[index]
            val illust = illusts.getOrNull(index)
            if (illust == null) {
                // Fewer than three illusts; leave the slot with its placeholder background.
                continue
            }
            val bitmap = withContext(Dispatchers.IO) {
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
            bitmap?.let {
                views.setImageViewBitmap(viewId, it)
                loadedCount++
            }

            val openIntent = Intent(context, VActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(Params.POSITION, 0)
                putExtra(Params.PAGE_UUID, UUID.randomUUID().toString())
                // Widget 刷新时不预占进程级 Container；点击后由 VActivity
                // 使用这份 bean 重建单页 PageData。
                putExtra(Params.WIDGET_ILLUST, illust)
            }
            val pi = PendingIntent.getActivity(
                context,
                widgetId * 41 + index,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(viewId, pi)
        }
        // 封面全军覆没时直接跳过：推一个没有图的 RemoteViews 会把 widget 上
        // 已经显示的画面抹成空卡（ColorOS 杀进程后台重跑时实测出现过）
        if (loadedCount == 0) return false

        manager.updateAppWidget(widgetId, views)
        return true
    }
}
