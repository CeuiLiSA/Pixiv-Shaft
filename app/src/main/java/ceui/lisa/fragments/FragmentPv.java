package ceui.lisa.fragments;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.utils.MyOnTabSelectedListener;
import ceui.pixiv.ui.pivision.PivisionFeedFragment;

public class FragmentPv extends BaseFragment<ViewpagerWithTablayoutBinding> {


    @Override
    public void initLayout() {
        mLayoutID = R.layout.viewpager_with_tablayout;
    }

    @NonNull
    @Override
    protected ViewpagerWithTablayoutBinding bindView(@NonNull View root) {
        return ViewpagerWithTablayoutBinding.bind(root);
    }

    @Override
    public void initView() {
        final String[] CHINESE_TITLES = new String[]{
                Shaft.getContext().getString(R.string.type_illust),
                Shaft.getContext().getString(R.string.type_manga)
        };
        // feeds 框架版特辑 tab,替代 legacy FragmentPivision
        final Fragment[] mFragments = new Fragment[]{
                PivisionFeedFragment.newInstance(PivisionFeedFragment.TYPE_ILLUST),
                PivisionFeedFragment.newInstance(PivisionFeedFragment.TYPE_MANGA)
        };

        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
        baseBind.toolbarTitle.setText(R.string.string_191);
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager(),
                FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
            @NonNull
            @Override
            public Fragment getItem(int position) {
                return mFragments[position];
            }

            @Override
            public int getCount() {
                return CHINESE_TITLES.length;
            }

            @Override
            public CharSequence getPageTitle(int position) {
                return CHINESE_TITLES[position];
            }
        });
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        MyOnTabSelectedListener listener = new MyOnTabSelectedListener(mFragments);
        baseBind.tabLayout.addOnTabSelectedListener(listener);
    }
}
