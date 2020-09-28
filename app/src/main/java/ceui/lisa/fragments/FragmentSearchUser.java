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
import ceui.lisa.repo.SearchUserRepo;
import ceui.lisa.utils.Params;

/**
 * 搜索用户
 */
public class FragmentSearchUser extends NetListFragment<FragmentBaseListBinding,
        ListUser, UserPreviewsBean> {

    private String word;

    public static FragmentSearchUser newInstance(String word) {
        Bundle args = new Bundle();
        args.putString(Params.KEY_WORD, word);
        FragmentSearchUser fragment = new FragmentSearchUser();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        word = bundle.getString(Params.KEY_WORD);
    }

    @Override
    public RemoteRepo<ListUser> repository() {
        return new SearchUserRepo(word);
    }

    @Override
    public BaseAdapter<UserPreviewsBean, RecyUserPreviewBinding> adapter() {
        return new UAdapter(allItems, mContext);
    }

    @Override
    public String getToolbarTitle() {
        return getString(R.string.string_236) + word;
    }
}
