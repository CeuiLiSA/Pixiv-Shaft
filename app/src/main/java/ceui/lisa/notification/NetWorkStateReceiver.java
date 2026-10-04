package ceui.lisa.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.blankj.utilcode.util.NetworkUtils;

import ceui.lisa.core.Manager;
import ceui.lisa.utils.DownloadLimitTypeUtil;

public class NetWorkStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        System.out.println("网络状态发生变化");
        // 「仅通过 Wi-Fi 下载」把网络变化当成停止信号 —— 离开 Wi-Fi 就把在跑的下载停掉，
        // 这是该模式自身的语义。无限制 / 不自动下载 忽略网络状态，切网不停任何下载。
        if (DownloadLimitTypeUtil.requiresWifi() && !NetworkUtils.isWifiConnected()) {
            Manager.get().stopAll();
        }
    }
}
