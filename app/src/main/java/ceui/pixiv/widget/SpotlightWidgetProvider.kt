package ceui.pixiv.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ceui.lisa.activities.Shaft
import java.util.concurrent.TimeUnit

class SpotlightWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        schedulePeriodic(context)
        triggerImmediate(context)
    }

    override fun onEnabled(context: Context) {
        schedulePeriodic(context)
        triggerImmediate(context)
    }

    // Intentionally NOT overriding onAppWidgetOptionsChanged: some launchers
    // (e.g. ColorOS) fire it 3–4× in rapid succession during initial relayout,
    // and triggering work with REPLACE on each turns the in-flight worker into
    // a thrash loop where none ever completes. Center-cropping handles resize.

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
    }

    private fun triggerImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<SpotlightWidgetWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    companion object {
        const val WORK_NAME_PERIODIC = "v3_spotlight_widget_periodic"
        const val WORK_NAME_ONE_SHOT = "v3_spotlight_widget_one_shot"

        /** 设置页改完换图间隔后也会直接调这里，UPDATE 策略让新周期立即生效 */
        @JvmStatic
        fun schedulePeriodic(context: Context) {
            val minutes = Shaft.sSettings.widgetRefreshIntervalMinutes.toLong()
            val request = PeriodicWorkRequestBuilder<SpotlightWidgetWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
