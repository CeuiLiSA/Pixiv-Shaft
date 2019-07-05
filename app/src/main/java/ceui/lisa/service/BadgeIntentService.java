//package ceui.lisa.service;
//
//import android.annotation.TargetApi;
//import android.app.IntentService;
//import android.app.Notification;
//import android.app.NotificationChannel;
//import android.app.NotificationManager;
//import android.content.Context;
//import android.content.Intent;
//import android.os.Build;
//
//import java.util.ArrayList;
//import java.util.List;
//
//import ceui.lisa.R;
//import ceui.lisa.utils.Common;
//import me.leolin.shortcutbadger.ShortcutBadger;
//
//public class BadgeIntentService extends IntentService {
//
//    private static final String NOTIFICATION_CHANNEL = "me.leolin.shortcutbadger.example";
//    public BadgeIntentService() {
//        super("BadgeIntentService");
//    }
//
//    public static List<Integer> added = new ArrayList<>();
//
//    private NotificationManager mNotificationManager;
//
//    @Override
//    public void onCreate() {
//        super.onCreate();
//        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
//    }
//
//    @Override
//    public void onStart(Intent intent, int startId) {
//        super.onStart(intent, startId);
//    }
//
//    @Override
//    protected void onHandleIntent(Intent intent) {
//        if (intent != null) {
//            int badgeCount = intent.getIntExtra("badgeCount", 0);
//
//            if(badgeCount == 10086){
//                Common.showLog("BadgeIntentService size " + added.size());
//
//                for (int i = 0; i < added.size(); i++) {
//                    Common.showLog("BadgeIntentService " + added.get(i));
//                    mNotificationManager.cancel(added.get(i));
//                }
//            }else {
//                int notificationId = Random.randomInt(100, 10000);
//                added.add(notificationId);
//                Common.showLog("BadgeIntentService size " + added.size());
//
//                Notification.Builder builder = new Notification.Builder(getApplicationContext())
//                        .setContentTitle("badgeCount " + badgeCount)
//                        .setContentText("notificationId " + notificationId)
//                        .setSmallIcon(R.drawable.logo_new_foreground);
//
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                    setupNotificationChannel();
//
//                    builder.setChannelId(NOTIFICATION_CHANNEL);
//                }
//
//                Notification notification = builder.build();
//                ShortcutBadger.applyNotification(getApplicationContext(), notification, badgeCount);
//                mNotificationManager.notify(notificationId, notification);
//            }
//        }
//    }
//
//    @TargetApi(Build.VERSION_CODES.O)
//    private void setupNotificationChannel() {
//        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL, "ShortcutBadger Sample",
//                NotificationManager.IMPORTANCE_DEFAULT);
//
//        mNotificationManager.createNotificationChannel(channel);
//    }
//}
