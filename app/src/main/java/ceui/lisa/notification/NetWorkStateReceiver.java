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

    /**
     * 上一次看到的 Wi-Fi 连接状态，null = 还没有基线。Shaft 只注册这一个实例，onReceive
     * 都在主线程，无需同步。
     *
     * 只在「进 / 出 Wi-Fi」时动作：CONNECTIVITY_ACTION 在蜂窝断流重连、换 Wi-Fi 热点时也会发。
     * 不看跃迁的话，用户在蜂窝上手动继续的下载过个隧道就会被 park（连带收回放行），
     * 换热点也会无谓地踢一次 pump。
     */
    private Boolean lastWifi;

    @Override
    public void onReceive(Context context, Intent intent) {
        System.out.println("网络状态发生变化");

        boolean wifi = NetworkUtils.isWifiConnected();
        Boolean wasWifi = lastWifi;
        lastWifi = wifi;

        // CONNECTIVITY_ACTION 是 sticky 广播：Shaft 里 registerReceiver 的那一刻就会投递一次
        // 「当前状态」。那不是网络变化，只拿来当基线 —— 照常处理会在每次启动时 triggerPump，
        // 把冷启动恢复出来、用户还没在弹窗里决定要不要继续的页直接拉起来。
        if (isInitialStickyBroadcast()) return;

        int action = actionFor(DownloadLimitTypeUtil.requiresWifi(), wasWifi, wifi);
        if (action == ACTION_NONE) return;

        if (action == ACTION_PARK) {
            // 离开 Wi-Fi：把在跑的下载退回**等待态**（不是暂停态）—— 用户设的是「只在 Wi-Fi
            // 下下」，不是「暂停」；回到 Wi-Fi 时下面的分支会把它们自动接续。
            // 已有的「用户手动放行」随之收回：用户在 Wi-Fi 下点的继续不等于同意之后走流量，
            // 否则批量队列会照旧拉行、triggerPump，把刚 park 下来的整批在蜂窝上重新跑起来。
            Manager.get().parkForNetwork();
            QueueDownloadManager queue = queueOf(context);
            if (queue != null) queue.onNetworkGateClosed();
            return;
        }

        // 回到 Wi-Fi：把闸门关闭期间停在「等待」的任务自动启动，否则它们会一直挂着 ——
        // Manager 侧的等待项（state=INIT）没有自己的定时器，批量队列消费者也最多要等一轮
        // 闸门轮询才会重新评估。
        // 只踢自动启动：triggerPump 只会派发 INIT 且未暂停的项，不会覆盖用户的手动暂停。
        Manager.get().triggerPump();
        QueueDownloadManager queue = queueOf(context);
        if (queue != null) queue.onNetworkGateOpened();
    }

    static final int ACTION_NONE = 0;
    static final int ACTION_PARK = 1;
    static final int ACTION_WAKE = 2;

    /**
     * 一次（非 sticky 的）网络变化该做什么。纯函数，便于单测。
     *
     *   - 只有「仅通过 Wi-Fi 下载」存在网络守门；无限制 / 不自动下载 忽略网络状态，切网不停
     *     任何下载，也没有「因网络而等待」的项可唤醒 → NONE；
     *   - 不是进出 Wi-Fi 的变化（蜂窝内重连、Wi-Fi 换热点）→ NONE；没有基线时按变化处理；
     *   - 离开 Wi-Fi → PARK；回到 Wi-Fi → WAKE。
     */
    static int actionFor(boolean requiresWifi, Boolean wasWifi, boolean wifi) {
        if (!requiresWifi) return ACTION_NONE;
        if (wasWifi != null && wasWifi == wifi) return ACTION_NONE;
        return wifi ? ACTION_WAKE : ACTION_PARK;
    }

    private static QueueDownloadManager queueOf(Context context) {
        Context app = context.getApplicationContext();
        return app instanceof ServicesProvider
                ? ((ServicesProvider) app).getQueueDownloadManager() : null;
    }
}
