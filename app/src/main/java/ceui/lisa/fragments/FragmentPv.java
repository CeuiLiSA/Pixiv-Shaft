package ceui.lisa.fragments;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import com.blankj.utilcode.util.BarUtils;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.utils.MyOnTabSelectedListener;

public class FragmentPv extends BaseFragment<ViewpagerWithTablayoutBinding> {


    @Override
    public void initLayout() {
        mLayoutID = R.layout.viewpager_with_tablayout;
    }

    @Override
    public void initView() {
        final String[] CHINESE_TITLES = new String[]{
                Shaft.getContext().getString(R.string.type_illust),
                Shaft.getContext().getString(R.string.type_manga)
        };
        final ListFragment[] mFragments = new ListFragment[]{
                FragmentPivision.newInstance("illust"),
                FragmentPivision.newInstance("manga")
        };

        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
        baseBind.toolbarTitle.setText(R.string.string_191);
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager(), 0) {
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
