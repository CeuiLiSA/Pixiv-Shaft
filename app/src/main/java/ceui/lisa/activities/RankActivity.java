package ceui.lisa.activities;

import android.graphics.Color;
import android.support.annotation.Nullable;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.widget.Toolbar;
import android.view.View;

import ceui.lisa.R;
import ceui.lisa.fragments.FragmentRank;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class RankActivity extends BaseActivity {

    private static final String[] CHINESE_TITLES = new String[]{"日榜", "每周", "每月", "男性向", "女性向", "原创", "新人", "R"};
    private FragmentRank[] allPages = new FragmentRank[]{null, null, null, null, null, null, null, null};

    @Override
    protected void initLayout() {
        mLayoutID = R.layout.activity_multi_view_pager;
    }

    @Override
    protected void initView() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        ViewPager mViewPager = findViewById(R.id.view_pager);
        mViewPager.setAdapter(new FragmentStatePagerAdapter(getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                if (allPages[i] == null) {
                    allPages[i] = FragmentRank.newInstance(i);
                }
                return allPages[i];
            }

            @Override
            public int getCount() {
                return CHINESE_TITLES.length;
            }

            @Nullable
            @Override
            public CharSequence getPageTitle(int position) {
                return CHINESE_TITLES[position];
            }


        });
        tabLayout.setupWithViewPager(mViewPager);
    }

    @Override
    protected void initData() {

    }
}
