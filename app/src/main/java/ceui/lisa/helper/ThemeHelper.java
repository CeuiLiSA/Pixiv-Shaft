package ceui.lisa.helper;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import ceui.lisa.R;

public class ThemeHelper {

    public enum ThemeType {
        DEFAULT_MODE(0, R.string.string_298),
        LIGHT_MODE(1, R.string.string_299),
        DARK_MODE(2, R.string.string_300);

        public int themeTypeIndex;
        private final int themeTypeNameResId;

        ThemeType(int themeTypeIndex, int themeTypeNameResId) {
            this.themeTypeIndex = themeTypeIndex;
            this.themeTypeNameResId = themeTypeNameResId;
        }

        @Override
        public String toString() {
            return String.valueOf(themeTypeIndex);
        }

        public String toDisplayString(Context context) {
            return context.getString(themeTypeNameResId);
        }
    }

    public static void applyTheme(AppCompatActivity activity, @NonNull ThemeType themePref) {
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
}