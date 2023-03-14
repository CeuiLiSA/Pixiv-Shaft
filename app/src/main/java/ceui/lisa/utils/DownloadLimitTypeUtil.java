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
     * 创建任务时自动开始任务
     * @return
     */
    public static boolean startTaskWhenCreate(){
        return Shaft.sSettings.getDownloadLimitType() == 0 || (Shaft.sSettings.getDownloadLimitType() == 1 && NetworkUtils.isWifiConnected());
    }

    /**
     * 目前是否可以下载
     * @return
     */
    public static boolean canDownloadNow(){
        return Shaft.sSettings.getDownloadLimitType() == 0 || (Shaft.sSettings.getDownloadLimitType() != 0 && NetworkUtils.isWifiConnected());
    }
}
