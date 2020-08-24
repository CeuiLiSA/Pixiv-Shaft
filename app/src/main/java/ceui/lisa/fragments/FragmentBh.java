package ceui.lisa.fragments;

import ceui.lisa.R;
import ceui.lisa.base.BaseFragment;
import ceui.lisa.databinding.FragmentBhBinding;

public class FragmentBh extends BaseFragment<FragmentBhBinding> {

    public static FragmentBh newInstance() {
        return new FragmentBh();
    }

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.fragment_bh;
    }
}
