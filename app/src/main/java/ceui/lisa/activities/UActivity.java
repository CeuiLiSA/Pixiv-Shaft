package ceui.lisa.activities;

import ceui.lisa.R;
import ceui.lisa.base.BaseActivity;
import ceui.lisa.databinding.ActivityUserNewBinding;

public class UActivity extends BaseActivity<ActivityUserNewBinding> {

    @Override
    protected int initLayout() {
        return R.layout.activity_user_new;
    }

    @Override
    protected void initView() {

    }

    @Override
    protected void initData() {

    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }
}
