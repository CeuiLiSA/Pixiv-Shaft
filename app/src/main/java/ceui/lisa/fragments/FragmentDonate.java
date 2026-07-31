package ceui.lisa.fragments;



import ceui.lisa.R;
import ceui.lisa.databinding.FragmentDonateBinding;

public class FragmentDonate extends BaseLazyFragment<FragmentDonateBinding> {

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

}
