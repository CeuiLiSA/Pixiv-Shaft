package ceui.lisa.base;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.databinding.ViewDataBinding;
import androidx.fragment.app.FragmentActivity;

public abstract class BaseActivity<Layout extends ViewDataBinding> extends AppCompatActivity {

    protected int mLayoutID = -1;
    protected FragmentActivity mActivity;
    protected Context mContext;
    protected Layout bind;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mActivity = this;
        mContext = this;

        Intent intent = getIntent();
        if (intent != null) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                initBundle(bundle);
            }
        }

        initLayout();

        if (mLayoutID != -1) {
            bind = DataBindingUtil.setContentView(mActivity, mLayoutID);
        }

        initView();
        initData();
    }

    protected void initBundle(Bundle bundle) {

    }

    protected void initView() {

    }

    protected void initData() {

    }

    protected abstract void initLayout();
}
