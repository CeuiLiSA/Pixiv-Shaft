package ceui.lisa.theme;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import ceui.lisa.activities.Shaft;
import ceui.lisa.utils.Common;

import static ceui.lisa.fragments.FragmentFilter.FILE_NAME;
import static ceui.lisa.fragments.FragmentFilter.THEME_NAME;

public class ThemeHelper {

    public static final String LIGHT_MODE = "白天模式（浅色）";
    public static final String DARK_MODE = "黑暗模式（深色）";
    public static final String DEFAULT_MODE = "默认模式（跟随系统）";

    public static void applyTheme(AppCompatActivity activity, @NonNull String themePref) {
        switch (themePref) {
            case LIGHT_MODE: {
                Common.showLog("切换成白天模式");
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                if (activity != null) {
                    activity.getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                }
                break;
            }
            case DARK_MODE: {
                Common.showLog("切换成夜晚模式");
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                if (activity != null) {
                    activity.getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                }
                break;
            }
            default: {
                Common.showLog("切换成默认模式");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                    if (activity != null) {
                        activity.getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                    }
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
                    if (activity != null) {
                        activity.getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY);
                    }
                }
                break;
            }
        }
    }

    public static int getThemeType() {
        String currentType = Shaft.sSettings.getThemeType();
        int index = 0;
        for (int i = 0; i < THEME_NAME.length; i++) {
            if (THEME_NAME[i].equals(currentType)) {
                index = i;
                break;
            }
        }
        return index;
    }
}