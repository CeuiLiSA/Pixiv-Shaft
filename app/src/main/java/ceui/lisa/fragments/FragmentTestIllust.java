package ceui.lisa.fragments;

import androidx.databinding.ViewDataBinding;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.ui.IPresent;
import ceui.lisa.ui.presenter.IllustPresenter;

public class FragmentTestIllust extends FragmentTest<ListIllust, IllustsBean> {

    @Override
    public IPresent<ListIllust> present() {
        return new IllustPresenter();
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }
}
