package ceui.lisa.fragments;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.utils.Dev;

public class FragmentNew extends BaseBindFragment<ViewpagerWithTablayoutBinding> {

    private static final String[] CHINESE_TITLES = new String[]{
            Shaft.getContext().getString(R.string.type_illust),
            Shaft.getContext().getString(R.string.type_manga),
            Shaft.getContext().getString(R.string.type_novel)};

    @Override
    void initLayout() {
        mLayoutID = R.layout.viewpager_with_tablayout;
    }

    @Override
    public void initView(View view) {
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
        baseBind.toolbar.setTitle("最新作品");
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
            @NonNull
            @Override
            public Fragment getItem(int position) {
                if (Dev.isDev) {
                    if (position == 0) {
                        return FragmentLatestNovel.newInstance("novel");
                    } else if (position == 1) {
                        return new FragmentBlank();
                    } else if (position == 2) {
                        return new FragmentBlank();
                    } else {
                        return new FragmentBlank();
                    }
                } else {
                    if (position == 0) {
                        return FragmentLatestWorks.newInstance("illust");
                    } else if (position == 1) {
                        return FragmentLatestWorks.newInstance("manga");
                    } else if (position == 2) {
                        return FragmentLatestNovel.newInstance("novel");
                    } else {
                        return new FragmentBlank();
                    }
                }
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
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
    }

    @Override
    void initData() {

    }
}
