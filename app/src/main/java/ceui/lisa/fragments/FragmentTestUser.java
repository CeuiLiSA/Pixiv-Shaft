package ceui.lisa.fragments;

import androidx.databinding.ViewDataBinding;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.ui.IModel;
import ceui.lisa.ui.IPresent;
import ceui.lisa.ui.model.ListModel;
import ceui.lisa.ui.model.UserListModel;
import ceui.lisa.ui.presenter.ListPresenter;
import ceui.lisa.ui.presenter.UserListPresenter;
import io.reactivex.Observable;

public class FragmentTestUser extends FragmentTest<ListUser, UserPreviewsBean> {

    @Override
    public IPresent<ListUser> present() {
        return new UserListPresenter();
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new UAdapter(allItems, mContext);
    }
}
