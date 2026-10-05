package ceui.lisa.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.blankj.utilcode.util.NetworkUtils;

import ceui.lisa.core.Manager;
import ceui.lisa.utils.DownloadLimitTypeUtil;
import ceui.pixiv.services.ServicesProvider;
import ceui.pixiv.ui.bulk.QueueDownloadManager;

public class NetWorkStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        System.out.println("网络状态发生变化");

        // CONNECTIVITY_ACTION 是 sticky 广播：Shaft 里 registerReceiver 的那一刻就会投递一次
        // 「当前状态」。那不是网络变化 —— 照常处理会在每次启动时 triggerPump，把冷启动恢复出来、
        // 用户还没在弹窗里决定要不要继续的页直接拉起来。
        if (isInitialStickyBroadcast()) return;

        // 「仅通过 Wi-Fi 下载」把网络变化当成停止信号 —— 离开 Wi-Fi 就把在跑的下载退回
        // **等待态**（不是暂停态），这是该模式自身的语义：用户设的是「只在 Wi-Fi 下下」，
        // 不是「暂停」；网络回来时下面的分支会把它们自动接续。
        // 无限制 / 不自动下载 忽略网络状态，切网不停任何下载。
        //
        // 已有的「用户手动放行」随之收回：用户在 Wi-Fi 下点的继续不等于同意之后走流量，
        // 否则批量队列会照旧拉行、triggerPump，把刚 park 下来的整批在蜂窝上重新跑起来。
        if (DownloadLimitTypeUtil.requiresWifi() && !NetworkUtils.isWifiConnected()) {
            Manager.get().parkForNetwork();
            QueueDownloadManager queue = queueOf(context);
            if (queue != null) queue.onNetworkGateClosed();
            return;
        }

        // 仅 Wi-Fi 重新连上 Wi-Fi：把闸门关闭期间停在「等待」的任务自动启动，否则它们会一直
        // 挂着 —— Manager 侧的等待项（state=INIT）没有自己的定时器，批量队列消费者也最多要等
        // 一轮闸门轮询才会重新评估。
        // 只有这一种模式存在「因网络而等待」的项：无限制下闸门恒开、没有可唤醒的，切网时踢
        // pump 只会把冷启动恢复出来、用户没让继续的页拉起来；不自动下载则从不自动开始。
        // 只踢自动启动：triggerPump 只会派发 INIT 且未暂停的项，不会覆盖用户的手动暂停。
        if (DownloadLimitTypeUtil.requiresWifi() && DownloadLimitTypeUtil.autoStartAllowed()) {
            Manager.get().triggerPump();
            QueueDownloadManager queue = queueOf(context);
            if (queue != null) queue.onNetworkGateOpened();
        }
    }

    private static QueueDownloadManager queueOf(Context context) {
        Context app = context.getApplicationContext();
        return app instanceof ServicesProvider
                ? ((ServicesProvider) app).getQueueDownloadManager() : null;
    }
}
