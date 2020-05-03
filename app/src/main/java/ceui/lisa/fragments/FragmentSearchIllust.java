package ceui.lisa.fragments;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.GridLayoutManager;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.BaseCtrl;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.view.GridItemDecoration;
import ceui.lisa.viewmodel.SearchModel;
import io.reactivex.Observable;

public class FragmentSearchIllust extends NetListFragment<FragmentBaseListBinding, ListIllust,
        IllustsBean, RecyIllustStaggerBinding> {

    private String token = "";
    private String keyWord = "";
    private String starSize = "";
    private String sort = "date_desc";
    private String searchTarget = "partial_match_for_tags";
    private boolean isPopular = false;
    private boolean hasR18 = false;

    private SearchModel searchModel;

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        searchModel = new ViewModelProvider(requireActivity()).get(SearchModel.class);
        searchModel.getKeyword().observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(String s) {
                Common.showLog(className + "开始刷新 " + s);
            }
        });
        super.onActivityCreated(savedInstanceState);
    }

    public static FragmentSearchIllust newInstance(String keyWord) {
        return newInstance(keyWord, "date_desc", "partial_match_for_tags");
    }

    public static FragmentSearchIllust newInstance(String keyWord, String sort) {
        return newInstance(keyWord, sort, "partial_match_for_tags");
    }

    public static FragmentSearchIllust newInstance(String keyWord, String sort,
                                                   String searchTarget) {
        Bundle args = new Bundle();
        args.putString(Params.KEY_WORD, keyWord);
        args.putString(Params.SORT_TYPE, sort);
        args.putString(Params.SEARCH_TYPE, searchTarget);
        args.putString(Params.STAR_SIZE, Shaft.sSettings.getSearchFilter().contains("无限制") ?
                "" : " " + (Shaft.sSettings.getSearchFilter()));
        FragmentSearchIllust fragment = new FragmentSearchIllust();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        keyWord = bundle.getString(Params.KEY_WORD);
        sort = bundle.getString(Params.SORT_TYPE);
        searchTarget = bundle.getString(Params.SEARCH_TYPE);
        starSize = bundle.getString(Params.STAR_SIZE);
    }


    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new IAdapter(allItems, mContext, true);
    }

    @Override
    public BaseCtrl present() {
        return new NetControl<ListIllust>() {
            @Override
            public Observable<ListIllust> initApi() {
                PixivOperate.insertSearchHistory(keyWord, 0);
                return Retro.getAppApi().searchIllust(token, keyWord, sort, searchTarget);
            }

            @Override
            public Observable<ListIllust> initNextApi() {
                return Retro.getAppApi().getNextIllust(token, mModel.getNextUrl());
            }
        };
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void initRecyclerView() {
        GridLayoutManager manager = new GridLayoutManager(mContext, 2);
        baseBind.recyclerView.setLayoutManager(manager);
        baseBind.recyclerView.addItemDecoration(new GridItemDecoration(2, DensityUtil.dp2px(8.0f), true));
    }
}
