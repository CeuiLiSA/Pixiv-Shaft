package ceui.lisa.utils;

import android.content.res.Resources;

import com.blankj.utilcode.util.NetworkUtils;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;

public class DownloadLimitTypeUtil {

    public static int[] DOWNLOAD_START_TYPE_IDS = new int[]{
            R.string.string_289,
            R.string.string_448,
            R.string.string_453
    };
    public static int getCurrentStatusIndex() {
        int currentIndex = Shaft.sSettings.getDownloadLimitType();
        if (currentIndex < 0 || currentIndex >= DOWNLOAD_START_TYPE_IDS.length) {
            currentIndex = 0;
        }
        return currentIndex;
    }

    /**
     * 创建任务时是否自动开始下载 —— 自动路径闸门。
     *
     * 口径：闸门只守「入列该不该自动启动下载」。用户触发的操作（下载管理里的继续 /
     * 重试 / 单条开始）忽略网络状态，不走这道闸门（见 QueueDownloadManager.resumeByUser）。
     */
    public static boolean startTaskWhenCreate(){
        return autoStartAllowed();
    }

    /** 读当前设置 + 当前网络，走一遍 [autoStartAllowed(int, boolean)]。 */
    public static boolean autoStartAllowed(){
        return autoStartAllowed(Shaft.sSettings.getDownloadLimitType(), NetworkUtils.isWifiConnected());
    }

    /**
     * 自动路径闸门（纯函数，便于单测；不触碰 Settings / Android）。
     *
     * 只回答一个问题：**入列时要不要自动开始下载**。
     *   0 无限制       → 任意网络都自动开始
     *   1 仅通过 Wi-Fi  → 仅 Wi-Fi 下自动开始
     *   2 不自动下载    → 从不自动开始，等用户在下载管理里手动启动
     * 其它（脏值）按 0 处理，与 [getCurrentStatusIndex] 的兜底口径保持一致。
     */
    public static boolean autoStartAllowed(int limitType, boolean wifiConnected){
        if (limitType == 1) return wifiConnected;
        if (limitType == 2) return false;
        return true;
    }

    /**
     * 当前设置是否「仅通过 Wi-Fi 下载」—— 只有这一种模式需要网络守门：
     * 离开 Wi-Fi 就把在跑的下载停掉（见 NetWorkStateReceiver）。
     * 无限制 / 不自动下载 忽略网络状态，切网不打断下载。
     */
    public static boolean requiresWifi(){
        return requiresWifi(Shaft.sSettings.getDownloadLimitType());
    }

    /** [requiresWifi()] 的纯函数版本，便于单测。 */
    public static boolean requiresWifi(int limitType){
        return limitType == 1;
    }
}
