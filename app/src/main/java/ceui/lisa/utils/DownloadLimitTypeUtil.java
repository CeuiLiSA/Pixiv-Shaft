package ceui.lisa.utils;

import android.content.res.Resources;

import com.blankj.utilcode.util.NetworkUtils;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;

public class DownloadLimitTypeUtil {

    private static final Resources resources = Shaft.getContext().getResources();

    public static String[] DOWNLOAD_START_TYPE_NAMES = new String[]{
            resources.getString(R.string.string_289),
            resources.getString(R.string.string_448),
            resources.getString(R.string.string_453)
    };

    public static String getCurrentStatusName() {
        int currentIndex = Shaft.sSettings.getDownloadLimitType();
        if (currentIndex < 0 || currentIndex >= DOWNLOAD_START_TYPE_NAMES.length) {
            currentIndex = 0;
        }
        return DOWNLOAD_START_TYPE_NAMES[currentIndex];
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
