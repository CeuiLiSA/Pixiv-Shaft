package ceui.lisa.activities;

import android.graphics.Color;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.widget.Toolbar;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.fragments.FragmentIllustList;
import ceui.lisa.fragments.FragmentSingleIllust;
import ceui.lisa.response.IllustsBean;

public class ViewPagerActivity extends BaseActivity{

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
    protected void initView() {
        mIllusts.addAll(Shaft.allIllusts);
        ViewPager viewPager = findViewById(R.id.view_pager);
        viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                return FragmentSingleIllust.newInstance(mIllusts.get(i));
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
