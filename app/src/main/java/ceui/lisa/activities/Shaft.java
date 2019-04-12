package ceui.lisa.activities;

import android.app.Application;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.response.IllustsBean;

public class Shaft extends Application {

    /**
     * 全局context
     */
    private static Context sContext = null;

    /**
     * 当前正在浏览的画集列表
     */
    public static List<IllustsBean> allIllusts = new ArrayList<>();


    /**
     * 状态栏高度，初始化
     */
    public static int statusHeight = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        //初始化context
        sContext = this;


        //计算状态栏高度并赋值
        statusHeight = 0;
        int resourceId = sContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusHeight = sContext.getResources().getDimensionPixelSize(resourceId);
        }
    }

    public static Context getContext() {
        return sContext;
    }

    public static void setContext(Context context) {
        sContext = context;
    }
}
