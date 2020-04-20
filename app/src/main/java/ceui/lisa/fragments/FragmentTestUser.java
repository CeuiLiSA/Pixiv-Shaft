package ceui.lisa.fragments;

import androidx.databinding.ViewDataBinding;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.ui.IPresent;
import ceui.lisa.ui.presenter.UserPresenter;

public class FragmentTestUser extends FragmentTest<ListUser, UserPreviewsBean> {

    @Override
    public IPresent<ListUser> present() {
        return new UserPresenter();
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new UAdapter(allItems, mContext);
    }
}
