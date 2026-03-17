package ceui.pixiv.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.http.Retro
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class IllustGridWidgetWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val token = Shaft.sUserModel?.access_token ?: return Result.success()

        val illusts = try {
            withContext(Dispatchers.IO) {
                Retro.getAppApi().getRecmdIllust(token, true)
                    .blockingFirst()
                    ?.illusts
                    ?.shuffled()
                    ?.take(6)
                    ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.retry()
        }

        if (illusts.isEmpty()) return Result.success()

        val bitmaps = withContext(Dispatchers.IO) {
            illusts.mapNotNull { illust ->
                try {
                    val cornerRadiusPx = (8 * context.resources.displayMetrics.density).toInt()
                    Glide.with(context)
                        .asBitmap()
                        .load(GlideUtil.getLargeImage(illust))
                        .apply(RequestOptions().transform(RoundedCorners(cornerRadiusPx)))
                        .submit(400, 400)
                        .get()
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
        }

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, IllustGridWidget::class.java)
        )

        for (widgetId in widgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_illust_grid)

            views.setViewVisibility(R.id.widget_loading_container, View.GONE)
            views.setViewVisibility(R.id.widget_grid_container, View.VISIBLE)

            bitmaps.forEachIndexed { index, bitmap ->
                if (index < IMAGE_VIEW_IDS.size) {
                    views.setImageViewBitmap(IMAGE_VIEW_IDS[index], bitmap)

                    val illust = illusts.getOrNull(index) ?: return@forEachIndexed
                    val clickIntent = Intent(context, VActivity::class.java)
                    clickIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    val pageData = PageData(listOf(illust))
                    Container.get().addPageToMap(pageData)
                    clickIntent.putExtra(Params.POSITION, 0)
                    clickIntent.putExtra(Params.PAGE_UUID, pageData.uuid)

                    val pendingIntent = android.app.PendingIntent.getActivity(
                        context,
                        widgetId * 10 + index,
                        clickIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(IMAGE_VIEW_IDS[index], pendingIntent)
                }
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        }

        return Result.success()
    }

    companion object {
        private val IMAGE_VIEW_IDS = intArrayOf(
            R.id.widget_image_1,
            R.id.widget_image_2,
            R.id.widget_image_3,
            R.id.widget_image_4,
            R.id.widget_image_5,
            R.id.widget_image_6,
        )
    }
}
