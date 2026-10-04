package ceui.lisa.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.blankj.utilcode.util.NetworkUtils;

import ceui.lisa.core.Manager;
import ceui.lisa.utils.DownloadLimitTypeUtil;
import ceui.pixiv.services.ServicesProvider;

public class NetWorkStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        System.out.println("网络状态发生变化");

        // 「仅通过 Wi-Fi 下载」把网络变化当成停止信号 —— 离开 Wi-Fi 就把在跑的下载退回
        // **等待态**（不是暂停态），这是该模式自身的语义：用户设的是「只在 Wi-Fi 下下」，
        // 不是「暂停」；网络回来时下面的分支会把它们自动接续。
        // 无限制 / 不自动下载 忽略网络状态，切网不停任何下载。
        if (DownloadLimitTypeUtil.requiresWifi() && !NetworkUtils.isWifiConnected()) {
            Manager.get().parkForNetwork();
            return;
        }

        // 换到了「可自动下载」的网络（无限制；或仅 Wi-Fi 重新连上 Wi-Fi）：把闸门关闭期间
        // 停在「等待」的任务自动启动，否则它们会一直挂着 —— Manager 侧的等待项（state=INIT）
        // 没有自己的定时器，批量队列消费者也最多要等一轮闸门轮询才会重新评估。
        // 只踢自动启动：triggerPump 只会派发 INIT 且未暂停的项，不会覆盖用户的手动暂停。
        if (DownloadLimitTypeUtil.autoStartAllowed()) {
            Manager.get().triggerPump();
            Context app = context.getApplicationContext();
            if (app instanceof ServicesProvider) {
                ((ServicesProvider) app).getQueueDownloadManager().onNetworkGateOpened();
            }
        }
    }
}
