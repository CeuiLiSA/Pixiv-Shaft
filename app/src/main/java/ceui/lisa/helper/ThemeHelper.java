package ceui.lisa.helper;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;

public class ThemeHelper {

    public static final String LIGHT_MODE = "白天模式（浅色）";
    public static final String DARK_MODE = "黑暗模式（深色）";
    public static final String DEFAULT_MODE = "默认模式（跟随系统）";

    public static void applyTheme(AppCompatActivity activity, @NonNull String themePref) {
        switch (themePref) {
            case LIGHT_MODE: {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                if (activity != null) {
                    activity.getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                }
                break;
            }
            case DARK_MODE: {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                if (activity != null) {
                    activity.getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                }
                break;
            }
            default: {
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

    public static int getThemeType(Context context) {
        String[] THEME_NAME = new String[]{
                context.getResources().getString(R.string.string_298),
                context.getResources().getString(R.string.string_299),
                context.getResources().getString(R.string.string_300)
        };
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