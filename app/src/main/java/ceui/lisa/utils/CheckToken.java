package ceui.lisa.utils;

public class CheckToken {


    private static long lastLoginTime = 0L;
    private static final long DURING = 50 * 60 * 1000L;
    private static String activeToken = "";


    /**
     *
     * @return
     */
    public static String getToken(){
        if(System.currentTimeMillis() - lastLoginTime > DURING){

            activeToken = refreshToken();
            return activeToken;

        }
        return activeToken;
    }

    private static String refreshToken(){
        String newToken;
        /**
         * 一顿瞎几把操作
         */
        newToken = "ACTIVE_TOKEN";
        lastLoginTime = System.currentTimeMillis();
        return newToken;
    }
}
