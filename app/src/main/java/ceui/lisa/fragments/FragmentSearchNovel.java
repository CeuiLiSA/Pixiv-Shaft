package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import ceui.lisa.repo.SearchNovelRepo;
import ceui.lisa.viewmodel.SearchModel;

public class FragmentSearchNovel extends NetListFragment<FragmentBaseListBinding, ListNovel,
        NovelBean> {

    private SearchModel searchModel;

    public static FragmentSearchNovel newInstance() {
        Bundle args = new Bundle();
        FragmentSearchNovel fragment = new FragmentSearchNovel();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initModel() {
        searchModel = new ViewModelProvider(requireActivity()).get(SearchModel.class);
        super.initModel();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        searchModel.getNowGo().observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(String s) {
                ((SearchNovelRepo) mRemoteRepo).update(searchModel);
                mRefreshLayout.autoRefresh();
            }
        });
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new NAdapter(allItems, mContext);
    }

    @Override
    public BaseRepo repository() {
        return new SearchNovelRepo(
                searchModel.getKeyword().getValue(),
                searchModel.getSortType().getValue(),
                searchModel.getSearchType().getValue(),
                searchModel.getStarSize().getValue()
        );
    }

    @Override
    public boolean showToolbar() {
        return false;
    }
}
