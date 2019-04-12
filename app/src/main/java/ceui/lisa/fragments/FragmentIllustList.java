package ceui.lisa.fragments;

import android.content.Intent;
import android.support.v7.widget.GridLayoutManager;
import android.view.View;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import ceui.lisa.activities.BlankActivity;
import ceui.lisa.adapters.IllustAdapter;
import ceui.lisa.interfs.OnItemClickListener;
import ceui.lisa.network.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.ListIllustResponse;
import ceui.lisa.utils.GridItemDecoration;
import io.reactivex.Observable;

public class FragmentIllustList extends BaseListFragment<ListIllustResponse, IllustAdapter, IllustsBean> {

    @Override
    Observable<ListIllustResponse> initApi() {
        return Retro.getAppApi().getRank("Bearer " + mUserModel.getResponse().getAccess_token(), "for_android", "day_male");
    }

    @Override
    void initAdapter() {
        mAdapter = new IllustAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                Intent intent = new Intent(mContext, BlankActivity.class);
                startActivity(intent);
            }
        });
        GridLayoutManager gridLayoutManager = new GridLayoutManager(mContext, 2);
        mRecyclerView.setLayoutManager(gridLayoutManager);
        mRecyclerView.addItemDecoration(new GridItemDecoration(2, DensityUtil.dp2px(8.0f), true));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(Refresh event) {
        event.nowFresh();
    };
}
