package ceui.lisa.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.VActivity
import ceui.lisa.core.Container
import ceui.lisa.core.PageData
import ceui.lisa.helper.AppLevelViewModelHelper
import ceui.lisa.http.NullCtrl
import ceui.lisa.http.Retro
import ceui.lisa.model.ListIllust
import ceui.lisa.models.IllustsBean
import ceui.lisa.notification.RecommendAppWidgetProvider.RecommendAppWidgetService
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.lisa.viewmodel.AppLevelViewModel
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.AppWidgetTarget
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Random
import java.util.stream.Collectors

class RecommendAppWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != null && intent.action == WIDGET_CLICK_ACTION) {
            if (WIDGET_CLICK_TYPE_IMAGE == intent.getStringExtra(WIDGET_CLICK_TYPE)) {
                val serializable = intent.getSerializableExtra(EXTRA_ILLUST_BEAN)
                val illustsBean = serializable as IllustsBean?
                val illustIntent = Intent(context, VActivity::class.java)
                illustIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val illustList = listOf(illustsBean)
                AppLevelViewModelHelper.updateFollowUserStatus(
                    illustsBean!!.user,
                    AppLevelViewModel.UpdateMethod.IF_ABSENT
                )
                val pageData = PageData(illustList)
                Container.get().addPageToMap(pageData)
                illustIntent.putExtra(Params.POSITION, 0)
                illustIntent.putExtra(Params.PAGE_UUID, pageData.uuid)
                context.startActivity(illustIntent)
            } else if (WIDGET_CLICK_TYPE_BTN == intent.getStringExtra(WIDGET_CLICK_TYPE)) {
//                int appWidgetID = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
//                if (appWidgetID != 0) {
//                    startService(context, new int[]{appWidgetID});
//                }
            } else if (ACTION_APPWIDGET_UPDATE == intent.action) {
                Log.d("Widget", "on update re")
                val views = RemoteViews(
                    context.packageName,
                    R.layout.recommend_illust_appwidget
                )
                val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)?.firstOrNull()
                val appWidgetManager = AppWidgetManager.getInstance(context)
                if (ids != null) {
                    appWidgetManager.updateAppWidget(ids, views)
                }
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            Log.d("Widget", "onUpdate]oi")
            MainScope().launch {
                val views = RemoteViews(
                    context.packageName,
                    R.layout.recommend_illust_appwidget
                )
                val intent = Intent(context, RecommendAppWidgetProvider::class.java)
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId));
                intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                views.setOnClickPendingIntent(
                    R.id.btn_refresh,
                    PendingIntent.getBroadcast(context, 880880, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                )

                Retro.getAppApi().getRecmdIllust(Shaft.sUserModel.access_token, true)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(object : NullCtrl<ListIllust?>() {
                        override fun success(listIllust: ListIllust?) {
                            listIllust ?: return
                            val randomIllust = listIllust.illusts.random()
                                val target = AppWidgetTarget(
                                    context,
                                    R.id.image_square,
                                    views,
                                    appWidgetId
                                )
                                Glide.with(context)
                                    .asBitmap()
                                    .load(GlideUtil.getSquare(randomIllust))
                                    .apply(RequestOptions().transform(RoundedCorners(20)))
                                    .into(target)

                            val illustIntent = Intent()
                            illustIntent.setClass(
                                context,
                                RecommendAppWidgetProvider::class.java
                            )
                            illustIntent.setAction(WIDGET_CLICK_ACTION)
                            illustIntent.putExtra(WIDGET_CLICK_TYPE, WIDGET_CLICK_TYPE_IMAGE)
                            illustIntent.putExtra(EXTRA_ILLUST_BEAN, randomIllust)
                            views.setOnClickPendingIntent(
                                R.id.image_square,
                                PendingIntent.getBroadcast(
                                    context,
                                    801,
                                    illustIntent,
                                    PendingIntent.FLAG_UPDATE_CURRENT
                                )
                            )

                            appWidgetManager.updateAppWidget(appWidgetId, views)
                        }
                    })
            }
        }
        //        startService(context, appWidgetIds);
        super.onUpdate(context, appWidgetManager, appWidgetIds)

    }
//    private fun getPendingSelfIntent(context: Context, action: Class<*>): PendingIntent {
//
//    }


    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        //关闭服务
//        val intent = Intent(context, RecommendAppWidgetService::class.java)
//        context.stopService(intent)
    }

    private fun startService(context: Context, appWidgetIds: IntArray) {
        val intent = Intent(context, RecommendAppWidgetService::class.java)
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
        ContextCompat.startForegroundService(context, intent)
    }

    class RecommendAppWidgetService : Service() {
        override fun onBind(intent: Intent): IBinder? {
            return null
        }

        override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    SERVICE_NOTIFICATION_CHANNEL_ID,
                    SERVICE_NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_NONE
                )
                chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                val service = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                service.createNotificationChannel(chan)
                val notificationBuilder =
                    NotificationCompat.Builder(this, SERVICE_NOTIFICATION_CHANNEL_ID)
                val notification = notificationBuilder
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .build()
                startForeground(1, notification)
            }
            if (Shaft.sUserModel == null) {
                return START_STICKY
            }
            Retro.getAppApi().getRecmdIllust(Shaft.sUserModel.access_token, true)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(object : NullCtrl<ListIllust?>() {
                    override fun success(listIllust: ListIllust?) {
                        listIllust ?: return
                        items.clear()
                        items.addAll(
                            listIllust.list.stream().filter { it: IllustsBean -> !it.isR18File }
                                .collect(Collectors.toList()))
                        Collections.shuffle(items)
                        val manager = AppWidgetManager.getInstance(this@RecommendAppWidgetService)
                        val idLs = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                        for (i in idLs!!.indices) {
                            val appID = idLs[i]
                            val randomIllust = items[i % items.size]
                            val views = RemoteViews(
                                this@RecommendAppWidgetService.packageName,
                                R.layout.recommend_illust_appwidget
                            )
                            val refreshIntent = Intent()
                            refreshIntent.setClass(
                                this@RecommendAppWidgetService,
                                RecommendAppWidgetProvider::class.java
                            )
                            refreshIntent.setAction(WIDGET_CLICK_ACTION)
                            refreshIntent.putExtra(WIDGET_CLICK_TYPE, WIDGET_CLICK_TYPE_BTN)
                            refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appID)
                            views.setOnClickPendingIntent(
                                R.id.btn_refresh,
                                PendingIntent.getBroadcast(
                                    this@RecommendAppWidgetService,
                                    randomRequestCode,
                                    refreshIntent,
                                    PendingIntent.FLAG_UPDATE_CURRENT
                                )
                            )
                            val illustIntent = Intent()
                            illustIntent.setClass(
                                this@RecommendAppWidgetService,
                                RecommendAppWidgetProvider::class.java
                            )
                            illustIntent.setAction(WIDGET_CLICK_ACTION)
                            illustIntent.putExtra(WIDGET_CLICK_TYPE, WIDGET_CLICK_TYPE_IMAGE)
                            illustIntent.putExtra(EXTRA_ILLUST_BEAN, randomIllust)
                            views.setOnClickPendingIntent(
                                R.id.image_square,
                                PendingIntent.getBroadcast(
                                    this@RecommendAppWidgetService,
                                    randomRequestCode,
                                    illustIntent,
                                    PendingIntent.FLAG_UPDATE_CURRENT
                                )
                            )
                            val target = AppWidgetTarget(
                                this@RecommendAppWidgetService,
                                R.id.image_square,
                                views,
                                appID
                            )
                            Glide.with(this@RecommendAppWidgetService)
                                .asBitmap()
                                .load(GlideUtil.getSquare(randomIllust))
                                .apply(RequestOptions().transform(RoundedCorners(20)))
                                .into(target)
                            manager.updateAppWidget(appID, views)
                        }
                    }
                })
            return START_STICKY
        }

        private val randomRequestCode: Int
            private get() = Random().nextInt(Int.MAX_VALUE) + 1

        companion object {
            private val items: MutableList<IllustsBean?> = ArrayList()
        }
    }

    companion object {
        private const val WIDGET_CLICK_ACTION = "ceui.lisa.pixiv.widget.click"
        private const val WIDGET_CLICK_TYPE = "ceui.lisa.pixiv.widget.click.type"
        private const val WIDGET_CLICK_TYPE_IMAGE = "ceui.lisa.pixiv.widget.click.type.image"
        private const val WIDGET_CLICK_TYPE_BTN = "ceui.lisa.pixiv.widget.click.type.btn"
        private const val EXTRA_ILLUST_BEAN = "app.widget.extra:illust.bean"
        private const val SERVICE_NOTIFICATION_CHANNEL_ID = "app_widget_service"
        private const val SERVICE_NOTIFICATION_CHANNEL_NAME = "App Widget Service"
    }
}

