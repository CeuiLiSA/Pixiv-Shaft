package ceui.lisa.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import ceui.lisa.core.Manager;

public class NetWorkStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        System.out.println("网络状态发生变化");
        Manager.get().stop();
    }
}