package ceui.lisa.fragments;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import ceui.lisa.repo.SearchNovelRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.viewmodel.SearchModel;
import io.reactivex.Observable;

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
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        searchModel = new ViewModelProvider(requireActivity()).get(SearchModel.class);
        searchModel.getNowGo().observe(this, new Observer<String>() {
            @Override
            public void onChanged(String s) {
                mRefreshLayout.autoRefresh();
            }
        });
        super.onActivityCreated(savedInstanceState);
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
                searchModel.getSearchType().getValue()
        );
    }

    @Override
    public boolean showToolbar() {
        return false;
    }
}
