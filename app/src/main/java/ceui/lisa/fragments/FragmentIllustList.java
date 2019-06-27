package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.http.Retro;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.SpacesItemDecoration;
import ceui.lisa.view.ScrollChangeManager;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentIllustList extends AutoClipFragment<ListIllustResponse, IllustStagAdapter, IllustsBean> {

    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getRank(sUserModel.getResponse().getAccess_token(), "day_male", null);
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(sUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
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
        mRecyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(4.0f)));
        ScrollChangeManager layoutManager =
                new ScrollChangeManager(2, ScrollChangeManager.VERTICAL);
        mRecyclerView.setLayoutManager(layoutManager);
    }
}
