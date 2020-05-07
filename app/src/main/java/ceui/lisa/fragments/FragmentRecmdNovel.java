package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.RankActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapterWithHeadView;
import ceui.lisa.adapters.NAdapter;
import ceui.lisa.adapters.NAdapterWithHeadView;
import ceui.lisa.adapters.NHAdapter;
import ceui.lisa.core.NetControl;
import ceui.lisa.databinding.FragmentBaseListBinding;
import ceui.lisa.databinding.FragmentRecmdBinding;
import ceui.lisa.databinding.RecyNovelBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.ListNovel;
import ceui.lisa.models.NovelBean;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.view.LinearItemDecoration;
import ceui.lisa.view.LinearItemHorizontalDecoration;
import ceui.lisa.view.LinearItemWithHeadDecoration;
import ceui.lisa.view.SpacesItemWithHeadDecoration;
import io.reactivex.Observable;

public class FragmentRecmdNovel extends NetListFragment<FragmentBaseListBinding,
        ListNovel, NovelBean> {

    private List<NovelBean> ranking = new ArrayList<>();

    @Override
    public NetControl<ListNovel> present() {
        return new NetControl<ListNovel>() {
            @Override
            public Observable<ListNovel> initApi() {
                return Retro.getAppApi().getRecmdNovel(Shaft.sUserModel.getResponse().getAccess_token());
            }

            @Override
            public Observable<ListNovel> initNextApi() {
                return Retro.getAppApi().getNextNovel(
                        Shaft.sUserModel.getResponse().getAccess_token(), mModel.getNextUrl());
            }
        };
    }

    @Override
    public BaseAdapter<NovelBean, RecyNovelBinding> adapter() {
        return new NAdapterWithHeadView(allItems, mContext);
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void onFirstLoaded(List<NovelBean> novelBeans) {
        ranking.addAll(mResponse.getRanking_novels());
        ((NAdapterWithHeadView) mAdapter).setHeadData(ranking);
    }

    @Override
    public void initRecyclerView() {
        mRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mRecyclerView.setHasFixedSize(true);
        mRecyclerView.addItemDecoration(new LinearItemWithHeadDecoration(DensityUtil.dp2px(12.0f)));
    }
}
