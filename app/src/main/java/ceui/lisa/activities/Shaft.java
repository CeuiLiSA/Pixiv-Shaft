package ceui.lisa.activities;

import android.app.Application;
import android.content.Context;

import com.scwang.smartrefresh.layout.SmartRefreshLayout;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.header.ClassicsHeader;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
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


    static {
        SmartRefreshLayout.setDefaultRefreshHeaderCreator((context, layout) -> {
            layout.setPrimaryColorsId(R.color.colorPrimary, android.R.color.white);//全局设置主题颜色
            return new ClassicsHeader(context);//.setTimeFormat(new DynamicTimeFormat("更新于 %s"));//指定为经典Header，默认是 贝塞尔雷达Header
        });

        SmartRefreshLayout.setDefaultRefreshFooterCreator((context, layout) ->
                new ClassicsFooter(context).setDrawableSize(20));
    }

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
