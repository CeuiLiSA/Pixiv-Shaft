package ceui.lisa.fragments;

import android.os.Bundle;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.repo.RelatedUserRepo;
import ceui.lisa.utils.Params;

public class FragmentRelatedUser extends NetListFragment<FragmentBaseListBinding, ListUser, UserPreviewsBean> {

    private int userID;

    public static FragmentRelatedUser newInstance(int userID) {
        Bundle args = new Bundle();
        args.putInt(Params.USER_ID, userID);
        FragmentRelatedUser fragment = new FragmentRelatedUser();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        userID = bundle.getInt(Params.USER_ID);
    }

    @Override
    public RemoteRepo<ListUser> repository() {
        return new RelatedUserRepo(userID);
    }

    @Override
    public BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> adapter() {
        return new UAdapter(allItems, mContext);
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.string_436);
    }
}
