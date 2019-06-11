package ceui.lisa.fragments;

import android.support.v7.widget.Toolbar;
import android.view.View;

import ceui.lisa.R;

public class FragmentDownloadFinish extends BaseFragment {

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    @Override
    View initView(View v) {
        Toolbar toolbar = v.findViewById(R.id.toolbar);
        toolbar.setVisibility(View.GONE);
        return v;
    }

    @Override
    void initData() {

    }
}
