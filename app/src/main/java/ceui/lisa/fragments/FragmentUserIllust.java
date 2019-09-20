package ceui.lisa.fragments;

import android.content.Intent;
import android.view.View;

import ceui.lisa.R;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustAdapter;
import ceui.lisa.http.Retro;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.model.ListIllustResponse;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.GridItemDecoration;
import ceui.lisa.view.GridScrollChangeManager;
import io.reactivex.Observable;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 某人創作的插畫
 */
public class FragmentUserIllust extends AutoClipFragment<ListIllustResponse, IllustAdapter, IllustsBean> {

    private int userID;

    public static FragmentUserIllust newInstance(int userID) {
        FragmentUserIllust fragmentRelatedIllust = new FragmentUserIllust();
        fragmentRelatedIllust.userID = userID;
        return fragmentRelatedIllust;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust_list;
    }

    @Override
    boolean showToolbar() {
        return false;
    }

    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getUserSubmitIllust(sUserModel.getResponse().getAccess_token(), userID, "illust");
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(sUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initRecyclerView() {
        super.initRecyclerView();
        GridScrollChangeManager manager = new GridScrollChangeManager(mContext, 2);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.addItemDecoration(new GridItemDecoration(2, DensityUtil.dp2px(4.0f), false));
    }

    @Override
    void initAdapter() {
        mAdapter = new IllustAdapter(allItems, mContext, mRecyclerView, mRefreshLayout);
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
