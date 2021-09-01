package ceui.lisa.fragments;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.UAdapter;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyUserPreviewBinding;
import ceui.lisa.model.ListUser;
import ceui.lisa.models.UserPreviewsBean;
import ceui.lisa.repo.SearchUserRepo;
import ceui.lisa.utils.Params;
import ceui.lisa.viewmodel.SearchModel;

/**
 * 搜索用户
 */
public class FragmentSearchUser extends NetListFragment<FragmentBaseListBinding,
        ListUser, UserPreviewsBean> {

    private String word;
    private SearchModel searchModel;

    public static FragmentSearchUser newInstance(String word) {
        Bundle args = new Bundle();
        args.putString(Params.KEY_WORD, word);
        FragmentSearchUser fragment = new FragmentSearchUser();
        fragment.setArguments(args);
        return fragment;
    }

    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        searchModel.getNowGo().observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(String s) {
                ((SearchUserRepo) mRemoteRepo).update(searchModel.getKeyword().getValue());
                mRefreshLayout.autoRefresh();
            }
        });
    }

    public void initModel() {
        searchModel = new ViewModelProvider(requireActivity()).get(SearchModel.class);
        super.initModel();
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
    public boolean showToolbar() {
        Activity mActivity = getActivity();
        return mActivity instanceof TemplateActivity;
    }

    /*@Override
    public String getToolbarTitle() {
        return getString(R.string.string_236) + word;
    }*/
}
