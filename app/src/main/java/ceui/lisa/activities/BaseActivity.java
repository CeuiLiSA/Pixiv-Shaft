package ceui.lisa.activities;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;
import androidx.fragment.app.FragmentActivity;
import ceui.lisa.R;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.pixiv.banner.BannerHostOwner;
import ceui.pixiv.download.DownloadsRegistry;
import ceui.pixiv.download.config.StorageChoice;

import static android.provider.DocumentsContract.EXTRA_INITIAL_URI;


public abstract class BaseActivity<Layout extends ViewDataBinding> extends AppCompatActivity implements BannerHostOwner {

    protected Context mContext;
    protected FragmentActivity mActivity;
    protected int mLayoutID;
    protected Layout baseBind;
    protected String className = this.getClass().getSimpleName() + " ";

    public static final int ASK_URI = 42;
    private FeedBack mFeedBack;

    /**
     * 把 MMKV 里持久化的 locale tag 转成 Configuration 包到 base context 上。
     * 这是「FragmentLogin 选完语言不 recreate、直接 startActivity 进下一页」也能正确显示语言的根。
     * AppCompat 自己的 per-app locale 持久化在 onboarding 那条路径上是空的（我们故意没调
     * setApplicationLocales 以避开 recreate），所以必须由这里兜底。
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ceui.pixiv.i18n.AppLocales.INSTANCE.wrapWithSavedLocale(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            updateTheme();

            mLayoutID = initLayout();

            mContext = this;
            mActivity = this;

            Intent intent = getIntent();
            if (intent != null) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    initBundle(bundle);
                }
            }

            // 系统栏统一走 EdgeToEdge：非全屏 Activity 让顶部 AppBar/Toolbar 的
            // fitsSystemWindows + colorPrimary 背景吃掉状态栏区域；
            // 全屏 Activity 走默认透明 scrim，由页面自己用 WindowInsets 处理。
            // 不再调 setDecorFitsSystemWindows(true) —— 它和 EdgeToEdge 互斥，
            // 在部分 OEM (HarmonyOS / EMUI) 上会让状态栏退回主题透明色而下方填 windowBackground (issue #853)。
            //
            // 状态栏图标固定走 dark()（= 浅色/白色图标）：状态栏区始终压在 colorPrimary
            // 这条深色头栏上，跟系统的日夜没关系。用 auto() 会在系统浅色模式下把图标转成
            // 黑色，黑图标叠蓝头栏看不清（issue #926，迁 EdgeToEdge 前是 setStatusBarColor
            // 直接配白图标）。导航栏仍走 auto()——它的背景跟随日夜内容色，图标该自适应。
            if (hideStatusBar()) {
                EdgeToEdge.enable(this);
            } else {
                int primaryColor = Common.resolveThemeAttribute(mContext, androidx.appcompat.R.attr.colorPrimary);
                EdgeToEdge.enable(this,
                        SystemBarStyle.dark(primaryColor),
                        SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT));
            }
            try {
                baseBind = DataBindingUtil.setContentView(mActivity, mLayoutID);
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            applyToolbarInsets();
            initModel();
            initView();
            initData();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void initModel() {

    }

    protected void initBundle(Bundle bundle) {

    }

    protected abstract int initLayout();

    protected abstract void initView();

    protected abstract void initData();

    public boolean hideStatusBar() {
        return false;
    }

    private void applyToolbarInsets() {
        if (baseBind == null) {
            return;
        }
        View toolbar = baseBind.getRoot().findViewById(R.id.toolbar);
        if (toolbar == null || !toolbar.getFitsSystemWindows()) {
            return;
        }
        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), insets.top, v.getPaddingRight(), 0);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    public static void newInstance(Intent intent, Context context) {
        context.startActivity(intent);
    }

    public void gray(boolean gray) {
        if (gray) {
            Paint grayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0.0f);
            grayPaint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
            getWindow().getDecorView().setLayerType(View.LAYER_TYPE_HARDWARE, grayPaint);
        } else {
            Paint normalPaint = new Paint();
            getWindow().getDecorView().setLayerType(View.LAYER_TYPE_HARDWARE, normalPaint);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ASK_URI) {
            if (resultCode != RESULT_OK || data == null) {
                return;
            }
            Uri treeUri = data.getData();
            if (treeUri == null) {
                return;
            }
            Common.showLog(className + "onActivityResult " + treeUri.toString());
            Shaft.sSettings.setRootPathUri(treeUri.toString());
            Local.setSettings(Shaft.sSettings);
            try {
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                mContext.getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
            } catch (SecurityException ignored) {
                // 某些 OEM 给了 tree URI 但拒绝持久化授权，本次会话内仍可用，不卡死流程。
            }
            // 把全局存储拨到 SAF；之前 FragmentSettings 在 setFeedBack 里干这件事，
            // 但 Activity 被回收时回调丢失，落库不完整。抬到 Activity 层后保证一定执行。
            try {
                DownloadsRegistry.applyGlobalStorage(new StorageChoice.Saf(treeUri));
            } catch (Throwable t) {
                t.printStackTrace();
            }
            Common.showToast(getString(R.string.saf_grant_success));
            doAfterGranted();
        }
    }

    public void doAfterGranted() {
        if (mFeedBack != null) {
            mFeedBack.doSomething();
        }
    }

    public void setFeedBack(FeedBack feedBack) {
        mFeedBack = feedBack;
    }

    private void updateTheme() {
        int current = Shaft.sSettings.getThemeIndex();
        // 自定义主题色（issue #1014）：覆盖 color 资源必须早于 setTheme，理由见 Shaft.updateTheme。
        if (ceui.pixiv.ui.settings.CustomThemeColor.isActive()) {
            ceui.pixiv.ui.settings.CustomThemeColor.applyResourceOverride(this);
            setTheme(R.style.AppTheme_Custom);
            return;
        }
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

    protected long tryParseId(String str) {
        try {
            return Long.parseLong(str);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return 0;
    }

    /**
     * 唯一一处启动 SAF 目录选择器的入口。所有「设置 SAF 目录」按钮都汇到这里。
     *
     * vivo OriginOS / Funtouch 在非 debuggable apk 上会拦掉 implicit
     * {@link Intent#ACTION_OPEN_DOCUMENT_TREE},silently 把请求重定向到 app 自己的
     * LAUNCHER —— 用户体感就是点 SAF 后切到 MainActivity 重启,落到首启动语言选择页;
     * debug apk 不走这条管控所以正常。这里先 resolveActivity 找到 DocumentsUI 的
     * ComponentName,再 setComponent 把 intent 改成 explicit,vivo 才不会动它。
     */
    public static void launchSafTreePicker(Activity activity) {
        if (activity == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> launchSafTreePicker(activity));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && Shaft.sSettings != null
                && !TextUtils.isEmpty(Shaft.sSettings.getRootPathUri())) {
            try {
                intent.putExtra(EXTRA_INITIAL_URI,
                        Uri.parse(Shaft.sSettings.getRootPathUri()));
            } catch (Exception ignored) {
            }
        }
        ComponentName component = intent.resolveActivity(activity.getPackageManager());
        if (component == null) {
            Common.showToast(activity.getString(R.string.saf_no_file_picker), true);
            return;
        }
        intent.setComponent(component);
        try {
            activity.startActivityForResult(intent, ASK_URI);
        } catch (Exception e) {
            Common.showToast(activity.getString(R.string.saf_no_file_picker), true);
        }
    }
}
