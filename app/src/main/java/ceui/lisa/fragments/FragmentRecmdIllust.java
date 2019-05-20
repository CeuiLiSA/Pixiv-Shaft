package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.StaggeredGridLayoutManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.IllustStagAdapter;
import ceui.lisa.interfs.Callable;
import ceui.lisa.interfs.OnItemClickListener;
import ceui.lisa.network.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.SpacesItemDecoration;
import io.reactivex.Observable;

/**
 * fragment recommend 推荐插画
 */
public class FragmentRecmdIllust extends BaseListFragment<ListIllustResponse, IllustStagAdapter, IllustsBean> {

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
        return Retro.getAppApi().getRecmdIllust(mUserModel.getResponse().getAccess_token(), false);
    }

    @Override
    Observable<ListIllustResponse> initNextApi() {
        return Retro.getAppApi().getNextIllust("Bearer " + mUserModel.getResponse().getAccess_token(), nextUrl);
    }

    @Override
    void initAdapter() {
        StaggeredGridLayoutManager layoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        mRecyclerView.setLayoutManager(layoutManager);
        mAdapter = new IllustStagAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                IllustChannel.getInstance().setIllustList(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
    }
}
