package ceui.lisa.fragments;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import com.blankj.utilcode.util.BarUtils;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;

public class FragmentNew extends BaseFragment<ViewpagerWithTablayoutBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.viewpager_with_tablayout;
    }

    @Override
    public void initView() {
        String[] CHINESE_TITLES = new String[]{
                Shaft.getContext().getString(R.string.type_illust),
                Shaft.getContext().getString(R.string.type_manga),
                Shaft.getContext().getString(R.string.type_novel)
        };
        BarUtils.setStatusBarColor(mActivity, android.R.attr.colorPrimary);
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
        baseBind.toolbarTitle.setText(R.string.string_204);
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
            @NonNull
            @Override
            public Fragment getItem(int position) {
                if (position == 0) {
                    return FragmentLatestWorks.newInstance("illust");
                } else if (position == 1) {
                    return FragmentLatestWorks.newInstance("manga");
                } else if (position == 2) {
                    return new FragmentLatestNovel();
                } else {
                    return new Fragment();
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
}
