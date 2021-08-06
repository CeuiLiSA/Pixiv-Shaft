package ceui.lisa.fragments;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import java.util.Arrays;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.repo.SearchIllustRepo;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivSearchParamUtil;
import ceui.lisa.viewmodel.SearchModel;

public class FragmentSearchIllust extends NetListFragment<FragmentBaseListBinding, ListIllust,
        IllustsBean> {

    private SearchModel searchModel;
    private boolean isPopular = false;

    public static FragmentSearchIllust newInstance(boolean popular) {
        Bundle args = new Bundle();
        //args.putBoolean(Params.IS_POPULAR, popular);
        FragmentSearchIllust fragment = new FragmentSearchIllust();
        fragment.setArguments(args);
        return fragment;
    }

    public static FragmentSearchIllust newInstance() {
        FragmentSearchIllust fragment = new FragmentSearchIllust();
        return fragment;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_base_list;
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
                if(!Arrays.asList(PixivSearchParamUtil.TAG_MATCH_VALUE).contains(searchModel.getSearchType().getValue())){
                    return;
                }
                ((SearchIllustRepo) mRemoteRepo).update(searchModel);
                if (isPopular) {
                    if (TextUtils.isEmpty(searchModel.getKeyword().getValue())) {
                        mRefreshLayout.setEnableRefresh(false);
                        return;
                    } else {
                        mRefreshLayout.setEnableRefresh(true);
                    }
                }
                mRefreshLayout.autoRefresh();
            }
        });
        // 监测侧滑过滤器中的收藏数选项变化
        searchModel.getStarSize().observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(String s) {
                ((SearchIllustRepo) mRemoteRepo).update(searchModel);
            }
        });
        searchModel.getSearchType().observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(String s) {
                ((SearchIllustRepo) mRemoteRepo).update(searchModel);
            }
        });
    }

    @Override
    protected void initBundle(Bundle bundle) {
        //isPopular = bundle.getBoolean(Params.IS_POPULAR);
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
                searchModel.getStarSize().getValue(),
                //isPopular,
                searchModel.getIsPremium().getValue(),
                searchModel.getStartDate().getValue(),
                searchModel.getEndDate().getValue()
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
