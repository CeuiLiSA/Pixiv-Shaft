package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.ViewPagerActivity;
import ceui.lisa.adapters.RAdapter;
import ceui.lisa.databinding.FragmentUserHorizontalBinding;
import ceui.lisa.interfaces.OnItemClickListener;
import ceui.lisa.model.IllustsBean;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.DensityUtil;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.view.LinearItemHorizontalDecoration;

/**
 * 榜单
 */
public class FragmentRankHorizontal extends BaseBindFragment<FragmentUserHorizontalBinding> {

    private List<IllustsBean> allItems = new ArrayList<>();
    private RAdapter mAdapter;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_user_horizontal;
    }

    @Override
    void initData() {
        baseBind.recyclerView.addItemDecoration(new LinearItemHorizontalDecoration(DensityUtil.dp2px(8.0f)));
        LinearLayoutManager manager = new LinearLayoutManager(mContext, LinearLayoutManager.HORIZONTAL, false);
        baseBind.recyclerView.setLayoutManager(manager);
        baseBind.recyclerView.setHasFixedSize(true);
        mAdapter = new RAdapter(allItems, mContext);
        mAdapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(View v, int position, int viewType) {
                IllustChannel.get().setIllustList(allItems);
                Intent intent = new Intent(mContext, ViewPagerActivity.class);
                intent.putExtra("position", position);
                startActivity(intent);
            }
        });
        baseBind.recyclerView.setAdapter(mAdapter);
    }

    @Override
    public boolean eventBusEnable() {
        return true;
    }

    @Override
    public void handleEvent(Channel channel) {
        allItems.clear();
        allItems.addAll(((List<IllustsBean>) channel.getObject()));
        mAdapter.notifyDataSetChanged();
        baseBind.progress.setVisibility(View.INVISIBLE);
    }
}
