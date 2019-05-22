package ceui.lisa.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.TextView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.activities.MultiViewPagerActivity;
import ceui.lisa.database.Channel;
import ceui.lisa.response.IllustsBean;

public class FragmentCenter extends BaseFragment {

    private RecyclerView mRecyclerView;

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_center;
    }

    @Override
    View initView(View v) {
        TextView textView = v.findViewById(R.id.go_rank);
        textView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, MultiViewPagerActivity.class);
                startActivity(intent);
            }
        });

        FragmentRankHorizontal fragmentRecmdUserHorizontal = new FragmentRankHorizontal();
        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();
        transaction.add(R.id.fragment_rank, fragmentRecmdUserHorizontal).commit();

        return v;
    }

    @Override
    void initData() {

    }
}
