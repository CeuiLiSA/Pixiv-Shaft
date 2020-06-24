package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentIllustBinding;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;

public class FragmentIllust extends BaseFragment<FragmentIllustBinding> {

    private IllustsBean illust;

    public static FragmentIllust newInstance(IllustsBean illustsBean) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, illustsBean);
        FragmentIllust fragment = new FragmentIllust();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        illust = (IllustsBean) bundle.getSerializable(Params.CONTENT);
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_illust;
    }

    @Override
    public void initView(View view) {
        if (illust != null) {
            baseBind.illustTitle.setText(illust.getTitle());
            Common.showLog(className + illust.getTitle());
        }
    }
}
