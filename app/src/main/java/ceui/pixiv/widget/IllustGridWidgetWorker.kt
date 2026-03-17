package ceui.pixiv.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ceui.lisa.R
import ceui.lisa.utils.GlideUrlChild
import ceui.loxia.Client
import ceui.loxia.Illust
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.HomeActivity
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class IllustGridWidgetWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("IllustGridWidget: doWork started")

        // SessionManager.initialize() 必须在主线程执行（LiveData.setValue 限制）
        withContext(Dispatchers.Main) {
            SessionManager.initialize()
        }

        if (!SessionManager.isLoggedIn) {
            Timber.w("IllustGridWidget: not logged in, skip")
            return Result.success()
        }

        val illusts = withContext(Dispatchers.IO) {
            try {
                Client.appApi.getHomeData("illust")
                    .illusts
                    .also { Timber.d("IllustGridWidget: fetched ${it.size} illusts") }
                    .shuffled()
                    .take(6)
            } catch (e: Exception) {
                Timber.e(e, "IllustGridWidget: API call failed")
                null
            }
        }

        if (illusts.isNullOrEmpty()) {
            Timber.w("IllustGridWidget: illusts empty")
            return Result.retry()
        }

        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(ComponentName(appContext, IllustGridWidget::class.java))
        if (ids.isEmpty()) {
            Timber.w("IllustGridWidget: no widget ids found")
            return Result.success()
        }

        val views = RemoteViews(appContext.packageName, R.layout.widget_illust_grid)
        val imageViewIds = listOf(
            R.id.widget_image_1, R.id.widget_image_2, R.id.widget_image_3,
            R.id.widget_image_4, R.id.widget_image_5, R.id.widget_image_6
        )

        withContext(Dispatchers.IO) {
            imageViewIds.forEachIndexed { index, viewId ->
                val illust = illusts.getOrNull(index) ?: return@forEachIndexed
                val bitmap = loadBitmap(illust)
                if (bitmap != null) {
                    views.setImageViewBitmap(viewId, bitmap)
                    val intent = Intent(appContext, HomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(EXTRA_ILLUST_ID, illust.id)
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        appContext, illust.id.toInt(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(viewId, pendingIntent)
                    Timber.d("IllustGridWidget: bitmap set for index $index, illustId=${illust.id}")
                } else {
                    Timber.w("IllustGridWidget: bitmap null for index $index")
                }
            }
        }

        ids.forEach { id -> manager.updateAppWidget(id, views) }
        Timber.d("IllustGridWidget: updateAppWidget done")
        return Result.success()
    }

    private fun loadBitmap(illust: Illust): Bitmap? {
        return try {
            Glide.with(appContext)
                .asBitmap()
                .load(GlideUrlChild(illust.image_urls?.square_medium))
                .submit(256, 256)
                .get()
        } catch (e: Exception) {
            Timber.e(e, "IllustGridWidget: Glide load failed for illust ${illust.id}")
            null
        }
    }

    companion object {
        const val EXTRA_ILLUST_ID = "widget_illust_id"

        fun enqueueImmediate(context: Context) {
            Timber.d("IllustGridWidget: enqueueImmediate")
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<IllustGridWidgetWorker>().build()
            )
        }
    }
}
