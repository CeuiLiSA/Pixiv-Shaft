package ceui.lisa.fragments;

import android.os.Bundle;

import androidx.databinding.ViewDataBinding;

import java.util.List;

import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.core.BaseCtrl;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.model.NovelSeries;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.Params;
import io.reactivex.Observable;

public class FragmentNovelSeries extends NetListFragment<FragmentBaseListBinding, NovelSeries,
        NovelBean, RecyNovelBinding>{

    private NovelBean novelBean;

    public static FragmentNovelSeries newInstance(NovelBean n) {
        Bundle args = new Bundle();
        args.putSerializable(Params.CONTENT, n);
        FragmentNovelSeries fragment = new FragmentNovelSeries();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initBundle(Bundle bundle) {
        novelBean = (NovelBean) bundle.getSerializable(Params.CONTENT);
    }

    @Override
    public BaseAdapter<?, ? extends ViewDataBinding> adapter() {
        return new NAdapter(allItems, mContext, true);
    }

    @Override
    public BaseCtrl present() {
        return new NetControl<NovelSeries>() {
            @Override
            public Observable<NovelSeries> initApi() {
                return Retro.getAppApi().getNovelSeries(mModel.getToken(), novelBean.getSeries().getId());
            }

            @Override
            public Observable<NovelSeries> initNextApi() {
                return Retro.getAppApi().getNextSeriesNovel(mModel.getToken(), mModel.getNextUrl());
            }
        };
    }

    @Override
    public String getToolbarTitle() {
        return "小说系列";
    }
}
