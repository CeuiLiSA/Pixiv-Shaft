package ceui.pixiv.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.http.Retro
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import com.bumptech.glide.Glide
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

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            ComponentName(context, IllustGridWidget::class.java)
        )

        updateAllWidgets(context, appWidgetManager, widgetIds, illusts)
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

        fun updateAllWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray,
            illusts: List<IllustsBean> = emptyList()
        ) {
            val token = Shaft.sUserModel?.access_token ?: return
            val actualIllusts = if (illusts.isEmpty()) {
                try {
                    Retro.getAppApi().getRecmdIllust(token, true)
                        .blockingFirst()
                        ?.illusts
                        ?.shuffled()
                        ?.take(6)
                        ?: emptyList()
                } catch (e: Exception) {
                    e.printStackTrace()
                    return
                }
            } else illusts

            if (actualIllusts.isEmpty()) return

            val bitmaps = actualIllusts.mapNotNull { illust ->
                try {
                    Glide.with(context)
                        .asBitmap()
                        .load(GlideUtil.getLargeImage(illust))
                        .submit(200, 200)
                        .get()
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            for (widgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_illust_grid)

                bitmaps.forEachIndexed { index, bitmap ->
                    if (index < IMAGE_VIEW_IDS.size) {
                        views.setImageViewBitmap(IMAGE_VIEW_IDS[index], bitmap)

                        val illust = actualIllusts.getOrNull(index) ?: return@forEachIndexed
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
        }
    }
}
