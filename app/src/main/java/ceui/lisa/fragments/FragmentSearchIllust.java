package ceui.lisa.fragments;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.core.BaseRepo;
import ceui.lisa.core.RemoteRepo;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.helper.TagFilter;
import ceui.lisa.http.NullCtrl;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.viewmodel.SearchModel;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

public class FragmentSearchIllust extends NetListFragment<FragmentBaseListBinding, ListIllust,
        IllustsBean> {

    private SearchModel searchModel;

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        searchModel = new ViewModelProvider(requireActivity()).get(SearchModel.class);
        searchModel.getNowGo().observe(getViewLifecycleOwner(), new Observer<String>() {
            @Override
            public void onChanged(String s) {
                mRefreshLayout.autoRefresh();
            }
        });
        super.onActivityCreated(savedInstanceState);
    }

    public static FragmentSearchIllust newInstance() {
        Bundle args = new Bundle();
        FragmentSearchIllust fragment = new FragmentSearchIllust();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new IAdapter(allItems, mContext);
    }

    @Override
    public BaseRepo repository() {
        return new RemoteRepo<ListIllust>() {
            @Override
            public Observable<ListIllust> initApi() {
                PixivOperate.insertSearchHistory(searchModel.getKeyword().getValue(), 0);
                return Retro.getAppApi().searchIllust(
                        token(),
                        searchModel.getKeyword().getValue() +
                                (Shaft.sSettings.getSearchFilter().contains("无限制") ?
                                        "" : " " + (Shaft.sSettings.getSearchFilter())),
                        searchModel.getSortType().getValue(),
                        searchModel.getSearchType().getValue());
            }

            @Override
            public Observable<ListIllust> initNextApi() {
                return Retro.getAppApi().getNextIllust(token(), mModel.getNextUrl());
            }

            @Override
            public ListIllust map(ListIllust response) {
                super.map(response);

                if (Shaft.sSettings.isDeleteStarIllust()) {
                    List<IllustsBean> tempList = PixivOperate.getListWithoutBooked(response);
                    response.setIllusts(tempList);
                }
                return response;
            }
        };
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
