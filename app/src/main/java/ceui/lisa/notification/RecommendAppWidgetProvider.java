package ceui.lisa.notification;

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.widget.RemoteViews;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.AppWidgetTarget;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import androidx.annotation.Nullable;
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
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class RecommendAppWidgetProvider extends AppWidgetProvider {

    private static final String WIDGET_CLICK_ACTION = "ceui.lisa.pixiv.widget.click";
    private static final String WIDGET_CLICK_TYPE = "ceui.lisa.pixiv.widget.click.type";
    private static final String WIDGET_CLICK_TYPE_IMAGE = "ceui.lisa.pixiv.widget.click.type.image";
    private static final String WIDGET_CLICK_TYPE_BTN = "ceui.lisa.pixiv.widget.click.type.btn";
    private static final String EXTRA_ILLUST_BEAN = "app.widget.extra:illust.bean";

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
                AppLevelViewModelHelper.fill(illustList);
                final PageData pageData = new PageData(illustList);
                Container.get().addPageToMap(pageData);
                illustIntent.putExtra(Params.POSITION, 0);
                illustIntent.putExtra(Params.PAGE_UUID, pageData.getUUID());
                context.startActivity(illustIntent);
            } else if (WIDGET_CLICK_TYPE_BTN.equals(intent.getStringExtra(WIDGET_CLICK_TYPE))) {
                RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.recommend_illust_appwidget);
                views.setImageViewResource(R.id.image_square, R.drawable.avatar);
                AppWidgetManager manager = AppWidgetManager.getInstance(context);
                int intExtra = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
                //Common.showToast(intExtra);
                if (intExtra != 0) {
                    manager.updateAppWidget(intExtra, views);
                } else {
                    manager.updateAppWidget(new ComponentName(context, RecommendAppWidgetProvider.class), views);
                }
            }
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);
        Intent intent = new Intent(context, RecommendAppWidgetService.class);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        //关闭服务
        Intent intent = new Intent(context, RecommendAppWidgetService.class);
        context.stopService(intent);
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
            Retro.getAppApi().getRecmdIllust(Shaft.sUserModel.getAccess_token())
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new NullCtrl<ListIllust>() {
                        @Override
                        public void success(ListIllust listIllust) {
                            items.clear();
                            items.addAll(listIllust.getList());

                            AppWidgetManager manager = AppWidgetManager.getInstance(RecommendAppWidgetService.this);
                            int[] idLs = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
                            for (int appID : idLs) {
                                IllustsBean randomIllust = items.get(new Random().nextInt(items.size()));
                                RemoteViews views = new RemoteViews(RecommendAppWidgetService.this.getPackageName(), R.layout.recommend_illust_appwidget);

                                Intent refreshIntent = new Intent();
                                refreshIntent.setClass(RecommendAppWidgetService.this, RecommendAppWidgetProvider.class);
                                refreshIntent.setAction(WIDGET_CLICK_ACTION);
                                refreshIntent.putExtra(WIDGET_CLICK_TYPE, WIDGET_CLICK_TYPE_BTN);
                                refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appID);
                                views.setOnClickPendingIntent(R.id.btn_refresh, PendingIntent.getBroadcast(RecommendAppWidgetService.this, appID, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT));

                                Intent illustIntent = new Intent();
                                illustIntent.setClass(RecommendAppWidgetService.this, RecommendAppWidgetProvider.class);
                                illustIntent.setAction(WIDGET_CLICK_ACTION);
                                illustIntent.putExtra(WIDGET_CLICK_TYPE, WIDGET_CLICK_TYPE_IMAGE);
                                illustIntent.putExtra(EXTRA_ILLUST_BEAN, randomIllust);
                                views.setOnClickPendingIntent(R.id.image_square, PendingIntent.getBroadcast(RecommendAppWidgetService.this, appID, illustIntent, PendingIntent.FLAG_UPDATE_CURRENT));

                                AppWidgetTarget target = new AppWidgetTarget(RecommendAppWidgetService.this, R.id.image_square, views, appID);
                                Glide.with(RecommendAppWidgetService.this)
                                        .asBitmap()
                                        .load(GlideUtil.getSquare(randomIllust))
                                        .into(target);

                                manager.updateAppWidget(appID, views);
                            }
                        }
                    });

            return super.onStartCommand(intent, flags, startId);
        }
    }
}
