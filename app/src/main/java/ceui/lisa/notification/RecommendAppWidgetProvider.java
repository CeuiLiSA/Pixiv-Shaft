package ceui.lisa.notification;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.widget.RemoteViews;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.AppWidgetTarget;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.VActivity;
import ceui.lisa.core.Container;
import ceui.lisa.core.PageData;
import ceui.lisa.helper.AppLevelViewModelHelper;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.viewmodel.AppLevelViewModel;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class RecommendAppWidgetProvider extends AppWidgetProvider {

    private static final String WIDGET_CLICK_ACTION = "ceui.lisa.pixiv.widget.click";
    private static final String WIDGET_CLICK_TYPE = "ceui.lisa.pixiv.widget.click.type";
    private static final String WIDGET_CLICK_TYPE_IMAGE = "ceui.lisa.pixiv.widget.click.type.image";
    private static final String WIDGET_CLICK_TYPE_BTN = "ceui.lisa.pixiv.widget.click.type.btn";
    private static final String EXTRA_ILLUST_BEAN = "app.widget.extra:illust.bean";
    private static final String SERVICE_NOTIFICATION_CHANNEL_ID = "app_widget_service";
    private static final String SERVICE_NOTIFICATION_CHANNEL_NAME = "App Widget Service";

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent.getAction() != null && intent.getAction().equals(WIDGET_CLICK_ACTION)) {
            if (WIDGET_CLICK_TYPE_IMAGE.equals(intent.getStringExtra(WIDGET_CLICK_TYPE))) {
                Serializable serializable = intent.getSerializableExtra(EXTRA_ILLUST_BEAN);
                IllustsBean illustsBean = (IllustsBean) serializable;
                Intent illustIntent = new Intent(context, VActivity.class);
                illustIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                List<IllustsBean> illustList = Collections.singletonList(illustsBean);
                AppLevelViewModelHelper.updateFollowUserStatus(illustsBean.getUser(), AppLevelViewModel.UpdateMethod.IF_ABSENT);
                final PageData pageData = new PageData(illustList);
                Container.get().addPageToMap(pageData);
                illustIntent.putExtra(Params.POSITION, 0);
                illustIntent.putExtra(Params.PAGE_UUID, pageData.getUUID());
                context.startActivity(illustIntent);
            } else if (WIDGET_CLICK_TYPE_BTN.equals(intent.getStringExtra(WIDGET_CLICK_TYPE))) {
//                int appWidgetID = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
//                if (appWidgetID != 0) {
//                    startService(context, new int[]{appWidgetID});
//                }
            }
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);
//        startService(context, appWidgetIds);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        //关闭服务
        Intent intent = new Intent(context, RecommendAppWidgetService.class);
        context.stopService(intent);
    }

    private void startService(Context context, int[] appWidgetIds) {
        Intent intent = new Intent(context, RecommendAppWidgetService.class);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds);
        ContextCompat.startForegroundService(context, intent);
    }

    public static class RecommendAppWidgetService extends Service {

        private static final List<IllustsBean> items = new ArrayList<>();

        @Nullable
        @org.jetbrains.annotations.Nullable
        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel chan = new NotificationChannel(SERVICE_NOTIFICATION_CHANNEL_ID, SERVICE_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_NONE);
                chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
                NotificationManager service = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
                service.createNotificationChannel(chan);

                NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, SERVICE_NOTIFICATION_CHANNEL_ID);
                Notification notification = notificationBuilder
                        .setOngoing(true)
                        .setCategory(Notification.CATEGORY_SERVICE)
                        .build();
                startForeground(1, notification);
            }
            if (Shaft.sUserModel == null) {
                return START_STICKY;
            }
            Retro.getAppApi().getRecmdIllust(Shaft.sUserModel.getAccess_token(), true)
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new NullCtrl<ListIllust>() {
                        @Override
                        public void success(ListIllust listIllust) {
                            items.clear();
                            items.addAll(listIllust.getList().stream().filter(it -> !it.isR18File()).collect(Collectors.toList()));
                            Collections.shuffle(items);

                            AppWidgetManager manager = AppWidgetManager.getInstance(RecommendAppWidgetService.this);
                            int[] idLs = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
                            for (int i = 0; i < idLs.length; i++) {
                                int appID = idLs[i];
                                IllustsBean randomIllust = items.get(i % items.size());
                                RemoteViews views = new RemoteViews(RecommendAppWidgetService.this.getPackageName(), R.layout.recommend_illust_appwidget);

                                Intent refreshIntent = new Intent();
                                refreshIntent.setClass(RecommendAppWidgetService.this, RecommendAppWidgetProvider.class);
                                refreshIntent.setAction(WIDGET_CLICK_ACTION);
                                refreshIntent.putExtra(WIDGET_CLICK_TYPE, WIDGET_CLICK_TYPE_BTN);
                                refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appID);
                                views.setOnClickPendingIntent(R.id.btn_refresh, PendingIntent.getBroadcast(RecommendAppWidgetService.this, getRandomRequestCode(), refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT));

                                Intent illustIntent = new Intent();
                                illustIntent.setClass(RecommendAppWidgetService.this, RecommendAppWidgetProvider.class);
                                illustIntent.setAction(WIDGET_CLICK_ACTION);
                                illustIntent.putExtra(WIDGET_CLICK_TYPE, WIDGET_CLICK_TYPE_IMAGE);
                                illustIntent.putExtra(EXTRA_ILLUST_BEAN, randomIllust);
                                views.setOnClickPendingIntent(R.id.image_square, PendingIntent.getBroadcast(RecommendAppWidgetService.this, getRandomRequestCode(), illustIntent, PendingIntent.FLAG_UPDATE_CURRENT));

                                AppWidgetTarget target = new AppWidgetTarget(RecommendAppWidgetService.this, R.id.image_square, views, appID);
                                Glide.with(RecommendAppWidgetService.this)
                                        .asBitmap()
                                        .load(GlideUtil.getSquare(randomIllust))
                                        .apply(new RequestOptions().transform(new RoundedCorners(20)))
                                        .into(target);

                                manager.updateAppWidget(appID, views);
                            }
                        }
                    });

            return START_STICKY;
        }

        private int getRandomRequestCode(){
            return new Random().nextInt(Integer.MAX_VALUE) + 1;
        }
    }
}
