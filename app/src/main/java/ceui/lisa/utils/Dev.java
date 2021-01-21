package ceui.lisa.utils;

public class Dev {

    //是否是开发状态
    public static boolean isDev = true;
    public static boolean refreshUser = false;

    public static String GLOABLE_HOST;
    public static boolean is_new_host;

    static {
        GLOABLE_HOST = Params.HOST_NAME;
        is_new_host = false;
    }

    /**
     * 测试账号：
     */
    public static final String USER_ACCOUNT = "user_wgzt2244";
    public static final String USER_PWD = "Lu9Zdnt3Ivb6LpWghB3rQgszHjbjw5qq";


}
