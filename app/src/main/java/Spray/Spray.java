package Spray;

import go.Seq;

public abstract class Spray {
    public static final long QueryRetryTimes = 5L;

    public static final String UserAgent = "go-pixiv";

    public static final String WfJSON = "application/dns-json";

    static  {
        Seq.touch();
        _init();
    }

    private static native void _init();

    public static native void startServer(String paramString);

    public static native void stopServer();

    public static void touch() {}
}
