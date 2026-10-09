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

    /**
     * 入列后是否该直接呈现为「暂停态」（paused=true）—— 只有「不自动下载」。
     *
     * 两种"没在跑"必须分开：
     *   - 等待态（INIT）是有自动接续方的中间态：仅 Wi-Fi 模式下离开 Wi-Fi 由
     *     {@code Manager.parkForNetwork()} 退回等待，回到 Wi-Fi 由 {@code NetWorkStateReceiver}
     *     唤醒；批量队列的页也在等自己的槽位。
     *   - 「不自动下载」没有任何自动唤醒源，继续显示「等待中」就是骗用户：卡片上的
     *     播放/暂停键按 isPaused 渲染成"暂停"图标，用户得先点一下暂停、再点一下继续
     *     才能真的开始。故入列即置 paused，如实呈现「已暂停」。
     *
     * 批量队列被用户手动放行后的补页（silent）不套这条规则 —— 那次点击本身就是手动启动
     * （见 {@code Manager.addTask}）。
     */
    public static boolean enqueueAsPaused(){
        return enqueueAsPaused(Shaft.sSettings.getDownloadLimitType());
    }

    /** [enqueueAsPaused()] 的纯函数版本，便于单测；只认模式 2，脏值按无限制处理，不误置暂停。 */
    static boolean enqueueAsPaused(int limitType){
        return limitType == 2;
    }
}
