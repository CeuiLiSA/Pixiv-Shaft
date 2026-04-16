package ceui.lisa.activities;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;
import androidx.fragment.app.FragmentActivity;
import ceui.lisa.R;
import ceui.lisa.interfaces.FeedBack;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;


public abstract class BaseActivity<Layout extends ViewDataBinding> extends AppCompatActivity {

    protected Context mContext;
    protected FragmentActivity mActivity;
    protected int mLayoutID;
    protected Layout baseBind;
    protected String className = this.getClass().getSimpleName() + " ";

    public static final int ASK_URI = 42;
    private FeedBack mFeedBack;

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

            int primaryColor = Common.resolveThemeAttribute(mContext, androidx.appcompat.R.attr.colorPrimary);
            if (hideStatusBar()) {
                EdgeToEdge.enable(this);
            } else {
                EdgeToEdge.enable(this,
                        SystemBarStyle.auto(primaryColor, primaryColor),
                        SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT));
                // 非透明状态栏的 Activity 不需要 edge-to-edge，
                // 让系统处理 insets，避免 fitsSystemWindows 产生多余 padding
                WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
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
            if (treeUri != null) {
                Common.showLog(className + "onActivityResult " + treeUri.toString());
                Shaft.sSettings.setRootPathUri(treeUri.toString());
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                mContext.getContentResolver().takePersistableUriPermission(treeUri,takeFlags);
                Common.showToast("授权成功！");
                Local.setSettings(Shaft.sSettings);
                doAfterGranted();
            }
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
}
