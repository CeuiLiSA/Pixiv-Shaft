package ceui.lisa.fragments;

import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.interfs.OnItemClickListener;
import ceui.lisa.network.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.SpacesItemDecoration;
import io.reactivex.Observable;

/**
 * fragment recommend 推荐图集
 */
public class FragmentRecmd extends BaseListFragment<ListIllustResponse, IllustStagAdapter, IllustsBean> {

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_blank;
    }

    @Override
    boolean showToolbar() {
        return false;
    }

    @Override
    Observable<ListIllustResponse> initApi() {
        //return Retro.getAppApi().getRecmdIllust("Bearer " + mUserModel.getResponse().getAccess_token(), "for_android", false);
        return null;
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust("Bearer " + mUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
        mAdapter = new IllustStagAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Shaft.allIllusts.clear();
                Shaft.allIllusts.addAll(allItems);
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
