package ceui.lisa.fragments;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.qmuiteam.qmui.skin.QMUISkinManager;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.qmuiteam.qmui.widget.dialog.QMUIDialogAction;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Params;

public class FragmentViewPager extends BaseFragment<ViewpagerWithTablayoutBinding> {

    private String title;

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
                    Shaft.getContext().getString(R.string.string_354),
            };
            Toolbar.OnMenuItemClickListener[] mFragments = new Toolbar.OnMenuItemClickListener[]{
                    new FragmentMutedTags(),
                    new FragmentMutedObjects()
            };
            baseBind.toolbar.inflateMenu(R.menu.delete_and_add);
            baseBind.toolbar.setOnMenuItemClickListener(mFragments[0]);
            baseBind.toolbarTitle.setText(R.string.muted_history);
            baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
                @NonNull
                @Override
                public Fragment getItem(int position) {
                    return (Fragment) mFragments[position];
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
                    baseBind.toolbar.setOnMenuItemClickListener(mFragments[position]);
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
            };
            baseBind.toolbarTitle.setText(R.string.string_r);
            baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
                @NonNull
                @Override
                public Fragment getItem(int position) {
                    return FragmentRankIllust.newInstance(position + 7, "", false);
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
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
    }
}
