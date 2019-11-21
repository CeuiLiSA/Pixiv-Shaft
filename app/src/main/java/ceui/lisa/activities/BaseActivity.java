package ceui.lisa.activities;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;

import com.blankj.utilcode.util.LanguageUtils;

import java.util.Locale;

public abstract class BaseActivity<Layout extends ViewDataBinding> extends AppCompatActivity {

    protected Context mContext;
    protected Activity mActivity;
    protected int mLayoutID;
    protected Layout baseBind;
    protected String className = this.getClass().getSimpleName() + " ";

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

        initView();
        initData();
    }


    protected abstract int initLayout();

    protected abstract void initView();

    protected abstract void initData();

    public boolean hideStatusBar() {
        return false;
    }
}
