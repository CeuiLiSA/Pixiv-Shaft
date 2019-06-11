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
import ceui.lisa.utils.SpacesItemDecoration;
import ceui.lisa.utils.ScrollChangeManager;
import io.reactivex.Observable;

/**
 * 相关插画
 */
public class FragmentRelatedIllust extends AutoClipFragment<ListIllustResponse, IllustStagAdapter, IllustsBean> {

    private int illustID;
    private String mTitle;

    public int getIllustID() {
        return illustID;
    }

    public void setIllustID(int illustID) {
        this.illustID = illustID;
    }

    public String getTitle() {
        return mTitle;
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    public static FragmentRelatedIllust newInstance(int id, String title){
        FragmentRelatedIllust fragmentRelatedIllust = new FragmentRelatedIllust();
        fragmentRelatedIllust.setIllustID(id);
        fragmentRelatedIllust.setTitle(title);
        return fragmentRelatedIllust;
    }

    @Override
    void initRecyclerView() {
        super.initRecyclerView();
        mRecyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(4.0f)));
    }

    @Override
    String getToolbarTitle() {
        return mTitle + "的相关作品";
    }

    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().relatedIllust(mUserModel.getResponse().getAccess_token(), illustID);
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust(mUserModel.getResponse().getAccess_token(), nextUrl);
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
