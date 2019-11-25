package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.RankActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.BaseAdapter;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.adapters.RAdapter;
import ceui.lisa.databinding.FragmentRecmdBinding;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.NetControl;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.view.LinearItemHorizontalDecoration;
import ceui.lisa.view.SpacesItemDecoration;
import io.reactivex.Observable;

public class FragmentRecmdManga extends NetListFragment<FragmentRecmdBinding,
        ListIllustResponse, IllustsBean, RecyIllustStaggerBinding> {

    @Override
    public NetControl<ListIllustResponse> present() {
        return new NetControl<ListIllustResponse>() {
            @Override
            public Observable<ListIllustResponse> initApi() {
                return Retro.getAppApi().getRecmdManga(Shaft.sUserModel.getResponse().getAccess_token());
            }

            @Override
            public Observable<ListIllustResponse> initNextApi() {
                return Retro.getAppApi().getNextIllust(Shaft.sUserModel.getResponse().getAccess_token(), nextUrl);
            }
        };
    }

    @Override
    public BaseAdapter<IllustsBean, RecyIllustStaggerBinding> adapter() {
        return new IAdapter(allItems, mContext).setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                DataChannel.get().setIllustList(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
    }

    @Override
    public void initView(View view) {
        super.initView(view);
        baseBind.seeMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, RankActivity.class);
                intent.putExtra("dataType", "漫画");
                startActivity(intent);
            }
        });
    }

    @Override
    public void initRecyclerView() {
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        baseBind.recyclerView.setLayoutManager(layoutManager);
        baseBind.recyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(8.0f)));
    }


    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_recmd;
    }

    @Override
    public String getToolbarTitle() {
        return "推荐漫画";
    }

    @Override
    public void firstSuccess() {
        List<IllustsBean> ranking = new ArrayList<>(mResponse.getRanking_illusts());
        baseBind.ranking.addItemDecoration(new LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f)));
        LinearLayoutManager manager = new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false);
        baseBind.ranking.setLayoutManager(manager);
        baseBind.ranking.setHasFixedSize(true);
        RAdapter adapter = new RAdapter(ranking, mContext);
        adapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                DataChannel.get().setIllustList(ranking);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
        baseBind.ranking.setAdapter(adapter);
    }
}
