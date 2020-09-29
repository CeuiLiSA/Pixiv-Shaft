package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.FilterMapper;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.repo.SearchIllustRepo;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.viewmodel.SearchModel;
import io.reactivex.Observable;
import io.reactivex.functions.Function;

public class FragmentSearchIllust extends NetListFragment<FragmentBaseListBinding, ListIllust,
        IllustsBean> {

    private SearchModel searchModel;
    private boolean isPopular = false;

    public static FragmentSearchIllust newInstance(boolean popular) {
        Bundle args = new Bundle();
        args.putBoolean(Params.IS_POPULAR, popular);
        FragmentSearchIllust fragment = new FragmentSearchIllust();
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
                ((SearchIllustRepo) mRemoteRepo).update(searchModel, isPopular);
                mRefreshLayout.autoRefresh();
            }
        });
    }

    @Override
    protected void initBundle(Bundle bundle) {
        isPopular = bundle.getBoolean(Params.IS_POPULAR);
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public BaseRepo repository() {
        return new SearchIllustRepo(
                searchModel.getKeyword().getValue(),
                searchModel.getSortType().getValue(),
                searchModel.getSearchType().getValue(),
                isPopular
        );
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void initRecyclerView() {
        staggerRecyclerView();
    }
}
