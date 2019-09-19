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

/**
 * 相关插画
 */
public class FragmentRelatedIllust extends FragmentList<ListIllustResponse, IllustsBean, RecyIllustStaggerBinding> {

    private int illustID;
    private String mTitle;

    public static FragmentRelatedIllust newInstance(int id, String title) {
        FragmentRelatedIllust fragmentRelatedIllust = new FragmentRelatedIllust();
        fragmentRelatedIllust.setIllustID(id);
        fragmentRelatedIllust.setTitle(title);
        return fragmentRelatedIllust;
    }

    public void setIllustID(int illustID) {
        this.illustID = illustID;
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    @Override
    public void initRecyclerView() {
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        baseBind.recyclerView.setLayoutManager(layoutManager);
        baseBind.recyclerView.addItemDecoration(new SpacesItemDecoration(DensityUtil.dp2px(8.0f)));
    }

    @Override
    public String getToolbarTitle() {
        return mTitle + "的相关作品";
    }

    @Override
    public Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().relatedIllust(sUserModel.getResponse().getAccess_token(), illustID);
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
