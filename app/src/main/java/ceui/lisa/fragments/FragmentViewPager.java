package ceui.lisa.fragments;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;
import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.MyOnTabSelectedListener;
import ceui.lisa.utils.Params;

public class FragmentViewPager extends BaseFragment<ViewpagerWithTablayoutBinding> {

    private String title;
    private ListFragment[] mFragments = null;

    public static FragmentViewPager newInstance(String title) {
        Bundle args = new Bundle();
        args.putString(Params.TITLE, title);
        FragmentViewPager fragment = new FragmentViewPager();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    protected void initBundle(Bundle bundle) {
        title = bundle.getString(Params.TITLE);
    }


    @Override
    public void initLayout() {
        mLayoutID = R.layout.viewpager_with_tablayout;
    }

    @Override
    public void initView() {
        if (TextUtils.equals(title, Params.VIEW_PAGER_MUTED)) {
            String[] CHINESE_TITLES = new String[]{
                    Shaft.getContext().getString(R.string.string_353),
                    Shaft.getContext().getString(R.string.string_381),
                    Shaft.getContext().getString(R.string.string_354),
            };
            mFragments = new ListFragment[]{
                    new FragmentMutedTags(),
                    new FragmentMutedUser(),
                    new FragmentMutedObjects(),
            };
            baseBind.toolbar.inflateMenu(R.menu.delete_and_add);
            baseBind.toolbar.setOnMenuItemClickListener((Toolbar.OnMenuItemClickListener) mFragments[0]);
            baseBind.toolbarTitle.setText(R.string.muted_history);
            baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
                @NonNull
                @Override
                public Fragment getItem(int position) {
                    return mFragments[position];
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
            baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

                }

                @Override
                public void onPageSelected(int position) {
                    baseBind.toolbar.setOnMenuItemClickListener((Toolbar.OnMenuItemClickListener) mFragments[position]);
                    if (position == 0) {
                        baseBind.toolbar.getMenu().clear();
                        baseBind.toolbar.inflateMenu(R.menu.delete_and_add);
                    } else {
                        baseBind.toolbar.getMenu().clear();
                        baseBind.toolbar.inflateMenu(R.menu.delete_muted_history);
                    }
                }

                @Override
                public void onPageScrollStateChanged(int state) {

                }
            });

        } else if (TextUtils.equals(title, Params.VIEW_PAGER_R18)) {

            if (Dev.hideMainActivityStatus) {
                baseBind.appBar.setPadding(0, Shaft.statusHeight, 0, 0);
            }

            baseBind.toolbar.setVisibility(View.GONE);
            String[] CHINESE_TITLES = new String[]{
                    Shaft.getContext().getString(R.string.r_eighteen),
                    Shaft.getContext().getString(R.string.r_eighteen_weekly_rank),
                    Shaft.getContext().getString(R.string.r_eighteen_male_rank),
                    Shaft.getContext().getString(R.string.r_eighteen_female_rank),
                    Shaft.getContext().getString(R.string.r_eighteen_ai_rank)
            };
            mFragments = new ListFragment[]{
//                    FragmentRankIllust.newInstance(7, "", false),
                    FragmentRankIllust.newInstance(8, "", false),
                    FragmentRankIllust.newInstance(9, "", false),
                    FragmentRankIllust.newInstance(10, "", false),
                    FragmentRankIllust.newInstance(11, "", false),
                    FragmentRankIllust.newInstance(12, "", false)
            };
            baseBind.toolbarTitle.setText(R.string.string_r);
            baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
                @NonNull
                @Override
                public Fragment getItem(int position) {
                    return mFragments[position];
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

        }
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        MyOnTabSelectedListener listener = new MyOnTabSelectedListener(mFragments);
        baseBind.tabLayout.addOnTabSelectedListener(listener);
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
    }

    public void forceRefresh() {
        try {
            mFragments[baseBind.viewPager.getCurrentItem()].forceRefresh();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
