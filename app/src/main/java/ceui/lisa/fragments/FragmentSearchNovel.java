package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import java.util.Arrays;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import ceui.lisa.R;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import ceui.lisa.repo.SearchNovelRepo;
import ceui.lisa.utils.PixivSearchParamUtil;
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
                if(!Arrays.asList(PixivSearchParamUtil.TAG_MATCH_VALUE_NOVEL).contains(searchModel.getSearchType().getValue())){
                    return;
                }
                ((SearchNovelRepo) mRemoteRepo).update(searchModel);
                mRefreshLayout.autoRefresh();
            }
        });
        // 监测侧滑过滤器中的收藏数选项变化
//        searchModel.getStarSize().observe(getViewLifecycleOwner(), new Observer<String>() {
//            @Override
//            public void onChanged(String s) {
//                ((SearchNovelRepo) mRemoteRepo).update(searchModel);
//            }
//        });
//        searchModel.getSearchType().observe(getViewLifecycleOwner(), new Observer<String>() {
//            @Override
//            public void onChanged(String s) {
//                ((SearchNovelRepo) mRemoteRepo).update(searchModel);
//            }
//        });
//        searchModel.getSortType().observe(getViewLifecycleOwner(), new Observer<String>() {
//            @Override
//            public void onChanged(String s) {
//                ((SearchNovelRepo) mRemoteRepo).update(searchModel);
//            }
//        });
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
                searchModel.getStarSize().getValue(),
                searchModel.getIsPremium().getValue(),
                searchModel.getStartDate().getValue(),
                searchModel.getEndDate().getValue(),
                searchModel.getR18Restriction().getValue()
        );
    }

    @Override
    public boolean showToolbar() {
        return false;
    }
}
