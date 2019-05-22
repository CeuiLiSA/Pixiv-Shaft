package ceui.lisa.fragments;

import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.ProgressBar;

import com.scwang.smartrefresh.layout.util.DensityUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.adapters.RankHorizontalAdapter;
import ceui.lisa.adapters.UserHorizontalAdapter;
import ceui.lisa.database.Channel;
import ceui.lisa.network.Retro;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.response.RecmdUserResponse;
import ceui.lisa.response.UserPreviewsBean;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.LinearItemHorizontalDecoration;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * 推荐用户
 */
public class FragmentRankHorizontal extends BaseFragment {

    private ProgressBar mProgressBar;
    private RecyclerView mRecyclerView;
    private List<IllustsBean> allItems = new ArrayList<>();
    private RankHorizontalAdapter mAdapter;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_user_horizontal;
    }

    @Override
    View initView(View v) {
        mProgressBar = v.findViewById(R.id.progress);
        mRecyclerView = v.findViewById(R.id.recyclerView);
        mRecyclerView.addItemDecoration(new LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f)));
        LinearLayoutManager manager = new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false);
        mRecyclerView.setLayoutManager(manager);
        mRecyclerView.setHasFixedSize(true);
        return v;
    }

    @Override
    void initData() {

    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(Channel<List<IllustsBean>> event) {

        Common.showLog("EVENTBUS 接受了消息");
        allItems.clear();
        allItems.addAll(event.getObject());
        mAdapter = new RankHorizontalAdapter(allItems, mContext);
        mRecyclerView.setAdapter(mAdapter);
        mProgressBar.setVisibility(View.INVISIBLE);

    }


    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        Common.showLog("EVENTBUS 注册了");
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        Common.showLog("EVENTBUS 取消注册了");
    }



}
