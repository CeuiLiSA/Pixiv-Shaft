package ceui.lisa.activities;

import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.View;


import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.fragments.FragmentSingleIllust;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.IllustChannel;

public class ViewPagerActivity extends BaseActivity {

    private List<IllustsBean> mIllusts = new ArrayList<>();

    @Override
    protected void initLayout() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mLayoutID = R.layout.activity_view_pager;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView() {
        mIllusts.addAll(IllustChannel.get().getIllustList());
        Bundle bundle = getIntent().getExtras();
        Common.showLog(bundle);
        ViewPager viewPager = findViewById(R.id.view_pager);
        viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                return FragmentSingleIllust.newInstance(mIllusts.get(i), bundle);
            }

            @Override
            public int getCount() {
                return mIllusts.size();
            }
        });
        int position = getIntent().getIntExtra("position", 0);
        viewPager.setCurrentItem(position);
    }

    @Override
    protected void initData() {

    }
}
