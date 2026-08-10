/*
 * Compatibility layer for Telegram Android's GPL-2.0 SpoilerEffect2.
 * It maps Telegram application utilities to Android framework APIs without changing the
 * upstream renderer, particle simulation, shaders, or frame pacing.
 */
package ceui.pixiv.widget.telegram;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.View;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.Random;

final class AndroidUtilities {
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    static final Point displaySize = new Point();
    static float screenRefreshRate = 60f;
    private static Context applicationContext;
    private static float density = 1f;

    private AndroidUtilities() {}

    static void initialize(View view) {
        Context context = view.getContext();
        applicationContext = context.getApplicationContext();
        density = context.getResources().getDisplayMetrics().density;
        displaySize.set(
                context.getResources().getDisplayMetrics().widthPixels,
                context.getResources().getDisplayMetrics().heightPixels
        );
        Display display = view.getDisplay();
        if (display != null && display.getRefreshRate() > 0f) {
            screenRefreshRate = display.getRefreshRate();
        }
    }

    static Context applicationContext() {
        return applicationContext;
    }

    static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }

    static float dpf2(float value) {
        return value * density;
    }

    static void cancelRunOnUIThread(Runnable runnable) {
        MAIN_HANDLER.removeCallbacks(runnable);
    }

    static void runOnUIThread(Runnable runnable) {
        MAIN_HANDLER.post(runnable);
    }

    static void runOnUIThread(Runnable runnable, long delay) {
        MAIN_HANDLER.postDelayed(runnable, delay);
    }

    static String readRes(int resourceId) {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                applicationContext.getResources().openRawResource(resourceId)
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result.toString();
    }
}

final class SharedConfig {
    static final int PERFORMANCE_CLASS_LOW = 0;
    static final int PERFORMANCE_CLASS_AVERAGE = 1;
    static final int PERFORMANCE_CLASS_HIGH = 2;

    private static final int[] LOW_SOC = {
            -1775228513, 802464304, 802464333, 802464302, 2067362118, 2067362060,
            2067362084, 2067362241, 2067362117, 2067361998, -1853602818
    };
    private static int devicePerformanceClass = -1;

    private SharedConfig() {}

    static int getDevicePerformanceClass() {
        if (devicePerformanceClass == -1) {
            devicePerformanceClass = measureDevicePerformanceClass();
        }
        return devicePerformanceClass;
    }

    // Same thresholds and low-SoC list as Telegram SharedConfig.measureDevicePerformanceClass().
    private static int measureDevicePerformanceClass() {
        Context context = AndroidUtilities.applicationContext();
        int androidVersion = Build.VERSION.SDK_INT;
        int cpuCount = Runtime.getRuntime().availableProcessors();
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        int memoryClass = activityManager.getMemoryClass();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL != null) {
            int hash = Build.SOC_MODEL.toUpperCase(Locale.ROOT).hashCode();
            for (int lowSoc : LOW_SOC) {
                if (lowSoc == hash) return PERFORMANCE_CLASS_LOW;
            }
        }

        int totalCpuFreq = 0;
        int freqResolved = 0;
        for (int i = 0; i < cpuCount; i++) {
            try (RandomAccessFile reader = new RandomAccessFile(String.format(
                    Locale.ENGLISH,
                    "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq",
                    i
            ), "r")) {
                String line = reader.readLine();
                if (line != null) {
                    totalCpuFreq += Integer.parseInt(line.trim()) / 1000;
                    freqResolved++;
                }
            } catch (Throwable ignore) {}
        }
        int maxCpuFreq = freqResolved == 0
                ? -1
                : (int) Math.ceil(totalCpuFreq / (float) freqResolved);

        long ram = -1;
        try {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            ram = memoryInfo.totalMem;
        } catch (Exception ignore) {}

        if (androidVersion < 21 ||
                cpuCount <= 2 ||
                memoryClass <= 100 ||
                cpuCount <= 4 && maxCpuFreq != -1 && maxCpuFreq <= 1250 ||
                cpuCount <= 4 && maxCpuFreq <= 1600 && memoryClass <= 128 && androidVersion <= 21 ||
                cpuCount <= 4 && maxCpuFreq <= 1300 && memoryClass <= 128 && androidVersion <= 24 ||
                ram != -1 && ram < 2L * 1024L * 1024L * 1024L) {
            return PERFORMANCE_CLASS_LOW;
        } else if (cpuCount < 8 ||
                memoryClass <= 160 ||
                maxCpuFreq != -1 && maxCpuFreq <= 2055 ||
                maxCpuFreq == -1 && cpuCount == 8 && androidVersion <= 23) {
            return PERFORMANCE_CLASS_AVERAGE;
        }
        return PERFORMANCE_CLASS_HIGH;
    }
}

final class Utilities {
    static final Random fastRandom = new Random();

    private Utilities() {}

    static float clamp(float value, float max, float min) {
        return Math.max(min, Math.min(max, value));
    }
}

final class FileLog {
    private static final String TAG = "SpoilerEffect2";

    private FileLog() {}

    static void e(String message) {
        Log.e(TAG, message);
    }

    static void e(Throwable throwable) {
        Log.e(TAG, "Telegram spoiler renderer error", throwable);
    }
}
