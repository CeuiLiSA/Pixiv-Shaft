package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.http.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.SpacesItemDecoration;
import ceui.lisa.view.ScrollChangeManager;
import io.reactivex.Observable;


public class FragmentRank extends AutoClipFragment<ListIllustResponse, IllustStagAdapter, IllustsBean> {

    private int mIndex = -1;
    private static final String[] API_TITLES = new String[]{"day", "week",
            "month","day_male", "day_female", "week_original", "week_rookie",
            "day_r18"};

    public static FragmentRank newInstance(int index){
        FragmentRank fragmentRank = new FragmentRank();
        fragmentRank.mIndex = index;
        return fragmentRank;
    }

    @Override
    boolean showToolbar() {
        return false;
    }

    @Override
    void initRecyclerView() {
        mRecyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(4.0f)));
    }


    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getRank(mUserModel.getResponse().getAccess_token(), API_TITLES[mIndex]);
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust("Bearer " + mUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
        ScrollChangeManager layoutManager =
                new ScrollChangeManager(2, ScrollChangeManager.VERTICAL);
        mRecyclerView.setLayoutManager(layoutManager);
        mAdapter = new IllustStagAdapter(allItems, mContext, mRecyclerView, mRefreshLayout);
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
