package ceui.pixiv.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.http.Retro
import ceui.lisa.model.RecmdIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.pixiv.ui.common.HomeActivity
import com.bumptech.glide.Glide

class IllustGridWidgetWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val token = Shaft.sUserModel?.access_token ?: return Result.success()

        val illusts = try {
            Retro.getAppApi()
                .getRecmdIllust(token, true)
                .blockingFirst()
                ?.illusts
                ?.filter { !it.isR18File }
                ?.shuffled()
                ?.take(6)
        } catch (e: Exception) {
            return Result.retry()
        }

        if (illusts.isNullOrEmpty()) return Result.success()

        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(
            ComponentName(appContext, IllustGridWidget::class.java)
        )
        if (ids.isEmpty()) return Result.success()

        val views = RemoteViews(appContext.packageName, R.layout.widget_illust_grid)

        val imageViewIds = listOf(
            R.id.widget_image_1,
            R.id.widget_image_2,
            R.id.widget_image_3,
            R.id.widget_image_4,
            R.id.widget_image_5,
            R.id.widget_image_6
        )

        val clickIntent = Intent(appContext, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val clickPendingIntent = PendingIntent.getActivity(
            appContext, 0, clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        imageViewIds.forEachIndexed { index, viewId ->
            val illust = illusts.getOrNull(index) ?: return@forEachIndexed
            val bitmap = loadBitmap(illust) ?: return@forEachIndexed
            views.setImageViewBitmap(viewId, bitmap)
            views.setOnClickPendingIntent(viewId, clickPendingIntent)
        }

        ids.forEach { id -> manager.updateAppWidget(id, views) }
        return Result.success()
    }

    private fun loadBitmap(illust: IllustsBean): Bitmap? {
        return try {
            Glide.with(appContext)
                .asBitmap()
                .load(GlideUtil.getSquare(illust))
                .submit(256, 256)
                .get()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<IllustGridWidgetWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
