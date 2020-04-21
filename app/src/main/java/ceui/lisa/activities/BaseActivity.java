package ceui.lisa.activities;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;
import androidx.fragment.app.FragmentActivity;

import ceui.lisa.R;

public abstract class BaseActivity<Layout extends ViewDataBinding> extends AppCompatActivity {

    protected Context mContext;
    protected FragmentActivity mActivity;
    protected int mLayoutID;
    protected Layout baseBind;
    protected String className = this.getClass().getSimpleName() + " ";

    public int navigationBarHeight;
    public int statusBarHeight;
    public boolean navigationBarOnButton, layoutFixed = false;
    public DisplayCutout displayCutout;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mLayoutID = initLayout();

        mContext = this;
        mActivity = this;


        if (hideStatusBar()) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }

        baseBind = DataBindingUtil.setContentView(mActivity, mLayoutID);

        //获取 navigationBar 和 statusBar 的高度
        //navigation_bar_height 和 navigation_bar_width 是相同的
        navigationBarHeight = -1;
        int resourceId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            navigationBarHeight = getResources().getDimensionPixelSize(resourceId);
        }
        resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        }

        if (Shaft.sSettings.isFullscreenLayout() && !disableFullscreenLayout()) { //判断是否启用全屏布局且 Activity 没有禁用全屏布局
            //见 https://developer.android.google.cn/guide/navigation/gesturenav
            getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                //等效于 <item name="android:navigationBarColor">@android:color/transparent</item>
                getWindow().setNavigationBarColor(getColor(android.R.color.transparent));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getResources().getBoolean(R.bool.is_night_mode)) {
                //等效于  <item name="android:windowLightNavigationBar">true</item>
                getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        }

        initView();
        initData();

        if (Shaft.sSettings.isFullscreenLayout() && !disableFullscreenLayout() && fixLayout() && fixTop()) {
            //如果启用全屏布局且 Activity 未禁用全屏布局且要求 fixLayout() 和 fixTop
            //就给 rootView 的 paddingTop 设为 statusBarHeight, 否则顶部会进入状态栏
            //这里不使用 Shaft.statusHeight 是因为状态栏高度与屏幕方向有关
            baseBind.getRoot().setPaddingRelative(0, statusBarHeight, 0, 0);
        }

        if (Shaft.sSettings.isFullscreenLayout() && disableFullscreenLayout()) {
            //如果启用全屏布局且 Activity 禁用全屏布局
            //那么恢复到启用全屏布局之前的样子
            getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()
                    & ~(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE));
        }

    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { //在 P 以上设陪获取 displayCutout 供子类使用
            displayCutout = baseBind.getRoot().getRootWindowInsets().getDisplayCutout();
        }
        if (Shaft.sSettings.isFullscreenLayout() && !disableFullscreenLayout()) {
            //如果启用全屏布局且 Activity 未禁用全屏布局
            if (fixLayout() && !layoutFixed) {
                //来自 https://stackoverflow.com/questions/21057035/detect-android-navigation-bar-orientation
                //判断导航栏方向的方法, 只能判断是否在底部
                layoutFixed = true;

                DisplayMetrics displayMetrics = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                int displayHeight = displayMetrics.heightPixels;
                int decorViewHeight = getWindow().getDecorView().getHeight();

                Log.d(className, "fixLayout: displayHeight = " + displayHeight);
                Log.d(className, "fixLayout: decorViewHeight " + decorViewHeight);

                if (decorViewHeight == displayHeight) {
                    //当导航栏在不在底部时恢复到启用全屏布局之前的样子
                    //否则视图会进入侧吗，面导航栏
                    Log.d(className, "fixLayout: nav bar on the side");
                    navigationBarOnButton = false;
                    getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()
                            & ~(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE));
                } else {
                    Log.d(className, "fixLayout: nav bar on the button");
                    navigationBarOnButton = true;
                }
            }

            //由于 Activity 恢复后设置会失效所以需要再次设置
            if (fixLayout() && layoutFixed && !navigationBarOnButton) {
                getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()
                        & ~(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE));
            }

            if (fixLayout() && fixTop() && navigationBarOnButton) {
                baseBind.getRoot().setPadding(0, statusBarHeight, 0, 0);
            } else {
                baseBind.getRoot().setPadding(0, 0, 0, 0);
            }
        }
    }

    protected abstract int initLayout();

    protected abstract void initView();

    protected abstract void initData();

    public boolean hideStatusBar() {
        return false;
    }

    public boolean fixLayout() {
        return true;
    }

    /**
     * 只在 fixLayout() == true 时有意义
     */
    public boolean fixTop() {
        return false;
    }

    /**
     * 用以在难以对全屏布局优化时禁用全屏布局
     */
    public boolean disableFullscreenLayout() {
        return false;
    }

    public static void newInstance(Intent intent, Context context) {
        context.startActivity(intent);
    }

    public void gray(boolean gray) {
        if (gray) {
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0.0f);
            grayPaint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
            getWindow().getDecorView().setLayerType(View.LAYER_TYPE_HARDWARE, grayPaint);
        } else {
            getWindow().getDecorView().setLayerType(View.LAYER_TYPE_HARDWARE, normalPaint);
        }
    }

    private Paint normalPaint = new Paint();
    private Paint grayPaint = new Paint();

}
