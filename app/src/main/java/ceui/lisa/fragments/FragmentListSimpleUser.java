package ceui.lisa.fragments;

import android.os.Bundle;

import androidx.databinding.ViewDataBinding;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.SimpleUserAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.model.ListSimpleUser;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.UserBean;
import ceui.lisa.repo.SimpleUserRepo;
import ceui.lisa.utils.Params;

public class FragmentListSimpleUser extends NetListFragment<FragmentBaseListBinding,
        ListSimpleUser, UserBean> {

    private IllustsBean illustsBean;

    public static FragmentListSimpleUser newInstance(IllustsBean illustsBean) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, illustsBean);
        FragmentListSimpleUser fragment = new FragmentListSimpleUser();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        illustsBean = (IllustsBean) bundle.getSerializable(Params.CONTENT);
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new SimpleUserAdapter(allItems, mContext);
    }

    @Override
    public BaseRepo repository() {
        return new SimpleUserRepo(illustsBean.getId());
    }

    @Override
    public String getToolbarTitle() {
        return "喜欢" + illustsBean.getTitle() + "的用户";
    }
}
