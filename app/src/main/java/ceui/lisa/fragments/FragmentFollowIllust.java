package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.LinearLayoutManager;
import android.view.View;

import com.github.ybq.android.spinkit.style.DoubleBounce;
import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.UserDetailActivity;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.EventAdapter;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.http.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.utils.LinearItemDecorationNoLR;
import io.reactivex.Observable;

/**
 *
 */
public class FragmentFollowIllust extends AutoClipFragment<ListIllustResponse, EventAdapter, IllustsBean> {

    @Override
    boolean showToolbar() {
        return false;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    @Override
    void initRecyclerView() {
        mRecyclerView.setBackgroundColor(getResources().getColor(R.color.follow_user_illust_divider));
        DoubleBounce doubleBounce = new DoubleBounce();
        doubleBounce.setColor(getResources().getColor(R.color.colorPrimary));
        mProgressBar.setIndeterminateDrawable(doubleBounce);
        mRecyclerView.addItemDecoration(new LinearItemDecorationNoLR(DensityUtil.dp2px(12.0f)));
        LinearLayoutManager manager = new LinearLayoutManager(mContext);
        mRecyclerView.setLayoutManager(manager);
    }


    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getFollowUserIllust(mUserModel.getResponse().getAccess_token());
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust("Bearer " + mUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
//        ScrollChangeManager layoutManager =
//                new ScrollChangeManager(2, ScrollChangeManager.VERTICAL);
//        mRecyclerView.setLayoutManager(layoutManager);
//        mAdapter = new IllustStagAdapter(allItems, mContext, mRecyclerView, mRefreshLayout);
//        mAdapter.setOnItemClickListener(new OnItemClickListener() {
//            @Override
//            public void onItemClick(View v, int position, int viewType) {
//                IllustChannel.get().setIllustList(allItems);
//                Intent intent = new Intent(mContext, ViewPagerActivity.class);
//                intent.putExtra("position", position);
//                startActivity(intent);
//            }
//        });
        mAdapter = new EventAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                if(viewType == 0) {
                    IllustChannel.get().setIllustList(allItems);
                    Intent intent = new Intent(mContext, ViewPagerActivity.class);
                    intent.putExtra("position", position);
                    startActivity(intent);
                }else if(viewType == 1){
                    Intent intent = new Intent(mContext, UserDetailActivity.class);
                    intent.putExtra("user id", allItems.get(position).getUser().getId());
                    startActivity(intent);
                }
            }
        });
    }
}
