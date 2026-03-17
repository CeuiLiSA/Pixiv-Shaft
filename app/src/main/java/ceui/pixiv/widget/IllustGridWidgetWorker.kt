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
import timber.log.Timber

class IllustGridWidgetWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        Timber.d("IllustGridWidget: doWork started")

        val token = Shaft.sUserModel?.access_token
        if (token == null) {
            Timber.w("IllustGridWidget: sUserModel is null, skip")
            return Result.success()
        }
        Timber.d("IllustGridWidget: token acquired")

        val illusts = try {
            Retro.getAppApi()
                .getRecmdIllust(token, true)
                .blockingFirst()
                ?.illusts
                ?.also { Timber.d("IllustGridWidget: fetched ${it.size} illusts") }
                ?.filter { !it.isR18File }
                ?.shuffled()
                ?.take(6)
        } catch (e: Exception) {
            Timber.e(e, "IllustGridWidget: API call failed")
            return Result.retry()
        }

        if (illusts.isNullOrEmpty()) {
            Timber.w("IllustGridWidget: illusts empty after filter")
            return Result.success()
        }
        Timber.d("IllustGridWidget: using ${illusts.size} illusts after filter")

        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(
            ComponentName(appContext, IllustGridWidget::class.java)
        )
        if (ids.isEmpty()) {
            Timber.w("IllustGridWidget: no widget ids found")
            return Result.success()
        }
        Timber.d("IllustGridWidget: updating ${ids.size} widget(s)")

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
            Timber.d("IllustGridWidget: loading bitmap for index $index, id=${illust.id}")
            val bitmap = loadBitmap(illust)
            if (bitmap != null) {
                views.setImageViewBitmap(viewId, bitmap)
                views.setOnClickPendingIntent(viewId, clickPendingIntent)
                Timber.d("IllustGridWidget: bitmap set for index $index")
            } else {
                Timber.w("IllustGridWidget: bitmap null for index $index, id=${illust.id}")
            }
        }

        ids.forEach { id -> manager.updateAppWidget(id, views) }
        Timber.d("IllustGridWidget: updateAppWidget done")
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
            Timber.e(e, "IllustGridWidget: Glide load failed for illust ${illust.id}")
            null
        }
    }

    companion object {
        fun enqueueImmediate(context: Context) {
            Timber.d("IllustGridWidget: enqueueImmediate")
            val request = OneTimeWorkRequestBuilder<IllustGridWidgetWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
