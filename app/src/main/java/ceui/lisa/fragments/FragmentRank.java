package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;


import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IAdapter;
import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.databinding.RecyIllustStaggerBinding;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.ScrollChangeManager;
import ceui.lisa.view.SpacesItemDecoration;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;


public class FragmentRank extends FragmentList<ListIllustResponse, IllustsBean, RecyIllustStaggerBinding> {

    private static final String[] API_TITLES = new String[]{"day", "week",
            "month", "day_male", "day_female", "week_original", "week_rookie",
            "day_r18"};
    private int mIndex = -1;
    private String queryDate = "";

    public static FragmentRank newInstance(int index, String date) {
        FragmentRank fragmentRank = new FragmentRank();
        fragmentRank.mIndex = index;
        fragmentRank.queryDate = date;
        return fragmentRank;
    }

    @Override
    public boolean showToolbar() {
        return false;
    }

    @Override
    public void initRecyclerView() {
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        baseBind.recyclerView.setLayoutManager(layoutManager);
        baseBind.recyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(8.0f)));
    }


    @Override
    public Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getRank(sUserModel.getResponse().getAccess_token(), API_TITLES[mIndex], queryDate);
    }

    @Override
    public Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(sUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    public void initAdapter() {
        mAdapter = new IAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                IllustChannel.get().setIllustList(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
    }
}
