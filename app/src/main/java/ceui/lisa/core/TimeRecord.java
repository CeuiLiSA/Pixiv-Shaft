package ceui.lisa.core;

import ceui.lisa.utils.Common;

public class TimeRecord {

    public static long startTime = 0L;
    public static long endTime = 0L;

    public static void start() {
        Common.showLog("TimeRecord start " + System.nanoTime());
        startTime = System.nanoTime();
        endTime = 0L;
    }

    public static void end() {
        Common.showLog("TimeRecord end " + System.nanoTime());
        endTime = System.nanoTime();
    }

    public static void result() {
        final long temp = endTime - startTime;
        Common.showLog("TimeRecord result 毫秒：" + temp/1000000L);
    }
}
