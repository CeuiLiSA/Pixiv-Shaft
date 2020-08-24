package ceui.lisa.fragments;

import com.scwang.smartrefresh.layout.SmartRefreshLayout;

import ceui.lisa.R;
import ceui.lisa.base.SwipeFragment;
import ceui.lisa.databinding.FragmentDonateBinding;

public class FragmentDonate extends SwipeFragment<FragmentDonateBinding> {

    public static FragmentDonate newInstance() {
        return new FragmentDonate();
    }

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_donate;
    }

    @Override
    protected void initView() {
        baseBind.toolbar.setNavigationOnClickListener(v -> finish());
    }

    @Override
    public SmartRefreshLayout getSmartRefreshLayout() {
        return baseBind.refreshLayout;
    }
}
