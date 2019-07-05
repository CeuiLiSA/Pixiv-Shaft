package ceui.lisa.fragments;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import ceui.lisa.R;
import ceui.lisa.activities.PikaActivity;
import ceui.lisa.http.ErrorCtrl;
import ceui.lisa.service.BadgeIntentService;
import ceui.lisa.utils.Common;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import me.leolin.shortcutbadger.ShortcutBadger;

public class FragmentMessage extends BaseFragment {

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_message;
    }

    @Override
    View initView(View v) {

        Button add = v.findViewById(R.id.add_message);
        add.setOnClickListener(view -> {
            setBadger(3);
        });
        Button delete = v.findViewById(R.id.delete_message);
        delete.setOnClickListener(view -> {
            clearAllBadger();
        });





        return v;
    }


    public String getEMUI() {
        Class<?> classType = null;
        String buildVersion = null;
        try {
            classType = Class.forName("android.os.SystemProperties");
            Method getMethod = classType.getDeclaredMethod("get", new Class<?>[]{String.class});
            buildVersion = (String) getMethod.invoke(classType, new Object[]{"ro.build.version.emui"});
        } catch (Exception e) {
            e.printStackTrace();
        }
        Common.showLog(className = buildVersion);
        return buildVersion;
    }



    public void setBadger(int count){
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        final String currentHomePackage;
        ResolveInfo resolveInfo = mActivity.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);

        // in case of duplicate apps (Xiaomi), calling resolveActivity from one will return null
        if (resolveInfo != null) {
            currentHomePackage = resolveInfo.activityInfo.packageName;
        }else {
            currentHomePackage = "none";
        }

        Common.showLog(className + currentHomePackage);

        if(currentHomePackage.contains("huawei")){

            String emuiVersion = getEMUI();
            if(!TextUtils.isEmpty(emuiVersion) && emuiVersion.contains("EmotionUI_8")){

                boolean fuck = ShortcutBadger.applyCount(mContext, count);
                Common.showLog(className + fuck);
            }else {

                mContext.startService(
                        new Intent(mContext, BadgeIntentService.class).putExtra("badgeCount", count)
                );
            }

        } else if(currentHomePackage.contains("miui")) {
            Observable.create(new ObservableOnSubscribe<String>() {
                @Override
                public void subscribe(ObservableEmitter<String> emitter) throws Exception {
                    Thread.sleep(1750L);
                    emitter.onNext("begin");
                }
            }).subscribeOn(Schedulers.computation())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new ErrorCtrl<String>() {
                        @Override
                        public void onNext(String s) {
                            if (s.equals("begin")) {
                                mContext.startService(
                                        new Intent(mContext, BadgeIntentService.class).putExtra("badgeCount", count)
                                );
                            }
                        }
                    });

        } else {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                mContext.startService(
                        new Intent(mContext, BadgeIntentService.class).putExtra("badgeCount", count)
                );
            } else {


                boolean fuck = ShortcutBadger.applyCount(mContext, count);
                Common.showLog(className + fuck);
            }
        }
    }

    private void clearAllBadger(){
        mContext.startService(
                new Intent(mContext, BadgeIntentService.class).putExtra("badgeCount", 10086)
        );
    }



    @Override
    void initData() {

    }
}
