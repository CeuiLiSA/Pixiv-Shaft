package ceui.lisa.activities;

import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;

import com.google.gson.Gson;
import com.scwang.smartrefresh.layout.SmartRefreshLayout;
import com.scwang.smartrefresh.layout.footer.ClassicsFooter;
import com.scwang.smartrefresh.layout.header.ClassicsHeader;

import ceui.lisa.R;
import ceui.lisa.helper.ThemeHelper;
import ceui.lisa.models.UserModel;
import ceui.lisa.notification.NetWorkStateReceiver;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.Settings;

import static ceui.lisa.utils.Local.LOCAL_DATA;

public class Shaft extends Application {

    public static UserModel sUserModel;
    public static Settings sSettings;
    public static Gson sGson;
    public static SharedPreferences sPreferences;
    protected NetWorkStateReceiver netWorkStateReceiver;

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
            return new ClassicsHeader(context);//.setTimeFormat(new DynamicTimeFormat("更新于 %s"));//指定为经典Header，默认是 贝塞尔雷达Header
        });

        SmartRefreshLayout.setDefaultRefreshFooterCreator((context, layout) ->
                new ClassicsFooter(context).setDrawableSize(20));
    }

    public static Context getContext() {
        return sContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        //初始化context
        sContext = this;
        sGson = new Gson();
        //0.0127254

        sPreferences = getSharedPreferences(LOCAL_DATA, Context.MODE_PRIVATE);

        sUserModel = Local.getUser();

        Dev.isDev = Local.getBoolean(Params.USE_DEBUG, false);

        sSettings = Local.getSettings();

        updateTheme();

        ThemeHelper.applyTheme(null, sSettings.getThemeType());

        //计算状态栏高度并赋值
        statusHeight = 0;
        int resourceId = sContext.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusHeight = sContext.getResources().getDimensionPixelSize(resourceId);
        }
        toolbarHeight = DensityUtil.dp2px(56.0f);

        if (netWorkStateReceiver == null) {
            netWorkStateReceiver = new NetWorkStateReceiver();
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(netWorkStateReceiver, filter);
    }

    private void updateTheme() {
        int current = Shaft.sSettings.getThemeIndex();
        switch (current) {
            case 0:
                setTheme(R.style.AppTheme_Index0);
                break;
            case 1:
                setTheme(R.style.AppTheme_Index1);
                break;
            case 2:
                setTheme(R.style.AppTheme_Index2);
                break;
            case 3:
                setTheme(R.style.AppTheme_Index3);
                break;
            case 4:
                setTheme(R.style.AppTheme_Index4);
                break;
            case 5:
                setTheme(R.style.AppTheme_Index5);
                break;
            case 6:
                setTheme(R.style.AppTheme_Index6);
                break;
            case 7:
                setTheme(R.style.AppTheme_Index7);
                break;
            case 8:
                setTheme(R.style.AppTheme_Index8);
                break;
            case 9:
                setTheme(R.style.AppTheme_Index9);
                break;
            default:
                setTheme(R.style.AppTheme_Default);
                break;
        }
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        try {
            super.unbindService(conn);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
