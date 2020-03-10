package ceui.lisa.activities;

import android.app.Application;
import android.content.Context;

import com.google.gson.Gson;
import com.scwang.smartrefresh.layout.SmartRefreshLayout;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.header.ClassicsHeader;

import ceui.lisa.R;
import ceui.lisa.models.UserModel;
import ceui.lisa.theme.ThemeHelper;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.Settings;

public class Shaft extends Application {

    public static UserModel sUserModel;
    public static Settings sSettings;
    public static Gson sGson;
    /**
     * 状态栏高度，初始化
     */
    public static int statusHeight = 0, toolbarHeight = 0;
    /**
     * 全局context
     */
    private static Context sContext = null;

    static {
        SmartRefreshLayout.setDefaultRefreshHeaderCreator((context, layout) -> {
            layout.setPrimaryColorsId(R.color.colorPrimary, android.R.color.white);//全局设置主题颜色
            return new ClassicsHeader(context);//.setTimeFormat(new DynamicTimeFormat("更新于 %s"));//指定为经典Header，默认是 贝塞尔雷达Header
        });

        SmartRefreshLayout.setDefaultRefreshFooterCreator((context, layout) ->
                new ClassicsFooter(context).setDrawableSize(20));
    }

    public static Context getContext() {
        return sContext;
    }

    public static void setContext(Context context) {
        sContext = context;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        //初始化context
        sContext = this;
        sGson = new Gson();
        //0.0127254

        final long before = System.nanoTime();

        sUserModel = Local.getUser();

        Dev.isDev = Local.getBoolean(Params.USE_DEBUG, false);

        final long after = System.nanoTime();

        Common.showLog("一共耗时 " + (after - before));

        sSettings = Local.getSettings();

        ThemeHelper.applyTheme(null, sSettings.getThemeType());

        //计算状态栏高度并赋值
        statusHeight = 0;
        int resourceId = sContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusHeight = sContext.getResources().getDimensionPixelSize(resourceId);
        }
        toolbarHeight = DensityUtil.dp2px(56.0f);

    }
}
