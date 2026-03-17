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
import ceui.lisa.fragments.FragmentLogin
import ceui.lisa.http.Retro
import ceui.lisa.models.IllustsBean
import ceui.lisa.utils.GlideUtil
import ceui.loxia.AccountResponse
import ceui.pixiv.session.SessionManager
import ceui.pixiv.ui.common.HomeActivity
import ceui.pixiv.utils.GSON_DEFAULT
import com.bumptech.glide.Glide
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import timber.log.Timber

class IllustGridWidgetWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Timber.d("IllustGridWidget: doWork started")

        var account = readAccountFromMMKV()
        if (account?.access_token == null) {
            Timber.w("IllustGridWidget: not logged in, skip")
            return@withContext Result.success()
        }

        val illusts = try {
            fetchIllusts("Bearer ${account.access_token}")
        } catch (e: HttpException) {
            if (e.code() == 400 || e.code() == 401) {
                Timber.w("IllustGridWidget: token expired, refreshing...")
                account = refreshToken(account) ?: run {
                    Timber.e("IllustGridWidget: token refresh failed")
                    return@withContext Result.retry()
                }
                try {
                    fetchIllusts("Bearer ${account.access_token}")
                } catch (e2: Exception) {
                    Timber.e(e2, "IllustGridWidget: API call failed after token refresh")
                    return@withContext Result.retry()
                }
            } else {
                Timber.e(e, "IllustGridWidget: API call failed")
                return@withContext Result.retry()
            }
        } catch (e: Exception) {
            Timber.e(e, "IllustGridWidget: API call failed")
            return@withContext Result.retry()
        }

        if (illusts.isNullOrEmpty()) {
            Timber.w("IllustGridWidget: illusts empty after filter")
            return@withContext Result.success()
        }
        Timber.d("IllustGridWidget: using ${illusts.size} illusts")

        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(ComponentName(appContext, IllustGridWidget::class.java))
        if (ids.isEmpty()) {
            Timber.w("IllustGridWidget: no widget ids found")
            return@withContext Result.success()
        }

        val views = RemoteViews(appContext.packageName, R.layout.widget_illust_grid)
        val imageViewIds = listOf(
            R.id.widget_image_1, R.id.widget_image_2, R.id.widget_image_3,
            R.id.widget_image_4, R.id.widget_image_5, R.id.widget_image_6
        )
        val clickPendingIntent = PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        imageViewIds.forEachIndexed { index, viewId ->
            val illust = illusts.getOrNull(index) ?: return@forEachIndexed
            val bitmap = loadBitmap(illust)
            if (bitmap != null) {
                views.setImageViewBitmap(viewId, bitmap)
                views.setOnClickPendingIntent(viewId, clickPendingIntent)
                Timber.d("IllustGridWidget: bitmap set for index $index")
            } else {
                Timber.w("IllustGridWidget: bitmap null for index $index")
            }
        }

        ids.forEach { id -> manager.updateAppWidget(id, views) }
        Timber.d("IllustGridWidget: updateAppWidget done")
        Result.success()
    }

    private fun fetchIllusts(token: String): List<IllustsBean>? {
        return Retro.getAppApi()
            .getRecmdIllust(token, true)
            .blockingFirst()
            ?.illusts
            ?.also { Timber.d("IllustGridWidget: fetched ${it.size} illusts") }
            ?.filter { !it.isR18File }
            ?.shuffled()
            ?.take(6)
    }

    private fun refreshToken(account: AccountResponse): AccountResponse? {
        return try {
            val refreshToken = account.refresh_token ?: return null
            val response = Retro.getAccountTokenApi().newRefreshToken2(
                FragmentLogin.CLIENT_ID,
                FragmentLogin.CLIENT_SECRET,
                FragmentLogin.REFRESH_TOKEN,
                refreshToken,
                true
            ).execute().body() ?: return null

            // 保存新 token 到 MMKV
            val prefStore = MMKV.mmkvWithID("shaft-session")
            prefStore.putString(SessionManager.USER_KEY, GSON_DEFAULT.toJson(response))
            Timber.d("IllustGridWidget: token refreshed successfully")
            response
        } catch (e: Exception) {
            Timber.e(e, "IllustGridWidget: token refresh exception")
            null
        }
    }

    private fun readAccountFromMMKV(): AccountResponse? {
        return try {
            val prefStore = MMKV.mmkvWithID("shaft-session")
            val json = prefStore.getString(SessionManager.USER_KEY, "") ?: ""
            if (json.isEmpty()) return null
            GSON_DEFAULT.fromJson(json, AccountResponse::class.java)
        } catch (e: Exception) {
            Timber.e(e, "IllustGridWidget: failed to read account from MMKV")
            null
        }
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
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<IllustGridWidgetWorker>().build()
            )
        }
    }
}
