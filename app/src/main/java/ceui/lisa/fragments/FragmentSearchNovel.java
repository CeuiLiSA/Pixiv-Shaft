package ceui.lisa.fragments;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.databinding.ViewDataBinding;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import ceui.lisa.activities.Shaft;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.BaseCtrl;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.ListIllust;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.PixivOperate;
import ceui.lisa.viewmodel.SearchModel;
import io.reactivex.Observable;

public class FragmentSearchNovel extends NetListFragment<FragmentBaseListBinding, ListNovel,
        NovelBean, RecyIllustStaggerBinding> {

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
    public BaseCtrl present() {
        return new NetControl<ListNovel>() {
            @Override
            public Observable<ListNovel> initApi() {
                return Retro.getAppApi().searchNovel(
                        token(),
                        searchModel.getKeyword().getValue(),
                        searchModel.getSortType().getValue(),
                        searchModel.getSearchType().getValue());
            }

            @Override
            public Observable<ListNovel> initNextApi() {
                return Retro.getAppApi().getNextNovel(token(), mModel.getNextUrl());
            }
        };
    }


    @Override
    public boolean showToolbar() {
        return false;
    }
}
