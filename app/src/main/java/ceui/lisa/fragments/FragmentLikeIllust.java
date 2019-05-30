package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.view.View;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.interfs.OnItemClickListener;
import ceui.lisa.network.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.utils.SpacesItemDecoration;
import io.reactivex.Observable;

/**
 * 某人收藏的插畫
 */
public class FragmentLikeIllust extends BaseListFragment<ListIllustResponse, IllustStagAdapter, IllustsBean> {

    private int userID;

    public static FragmentLikeIllust newInstance(int userID){
        FragmentLikeIllust fragmentRelatedIllust = new FragmentLikeIllust();
        fragmentRelatedIllust.userID = userID;
        return fragmentRelatedIllust;
    }

    @Override
    boolean showToolbar() {
        return false;
    }

    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getUserLikeIllust(mUserModel.getResponse().getAccess_token(), userID, "public");
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(mUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
        mAdapter = new IllustStagAdapter(allItems, mContext);
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
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        mRecyclerView.setLayoutManager(layoutManager);
    }
}
