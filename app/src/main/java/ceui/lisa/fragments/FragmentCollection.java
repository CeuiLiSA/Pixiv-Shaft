package ceui.lisa.fragments;

import android.content.Intent;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.ToxicBakery.viewpager.transforms.DrawerTransformer;
import com.google.android.material.tabs.TabLayout;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.utils.Params;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentCollection extends BaseFragment<ViewpagerWithTablayoutBinding> {

    private Fragment[] allPages;


    @Override
    public void initLayout() {
        mLayoutID = R.layout.viewpager_with_tablayout;
    }

    @Override
    public void initView() {
        String[] CHINESE_TITLES = new String[]{
            Shaft.getContext().getString(R.string.public_like_illust),
            Shaft.getContext().getString(R.string.private_like_illust),
            Shaft.getContext().getString(R.string.public_like_user),
            Shaft.getContext().getString(R.string.private_like_user),
            Shaft.getContext().getString(R.string.public_like_novel),
            Shaft.getContext().getString(R.string.private_like_novel)
        };
        mActivity.getWindow().setStatusBarColor(getResources().getColor(R.color.new_color_primary));
        baseBind.toolbarTitle.setText(R.string.bookmark);
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (baseBind.viewPager.getCurrentItem() == 0) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_KEYWORD,
                            Params.TYPE_PUBLUC);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签筛选");
                    startActivity(intent);
                    return true;
                } else if (baseBind.viewPager.getCurrentItem() == 1) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_KEYWORD,
                            Params.TYPE_PRIVATE);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "按标签筛选");
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });
        baseBind.viewPager.setPageTransformer(true, new DrawerTransformer());
        allPages = new Fragment[]{null, null, null, null, null, null};
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager(), 0) {
            @NonNull
            @Override
            public Fragment getItem(int i) {
                return getFragment(i);
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
        baseBind.tabLayout.setTabMode(TabLayout.MODE_SCROLLABLE);
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        baseBind.toolbar.inflateMenu(R.menu.illust_filter);
        baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i1) {

            }

            @Override
            public void onPageSelected(int i) {
                baseBind.toolbar.getMenu().clear();
                if (i == 0 || i == 1) {
                    baseBind.toolbar.inflateMenu(R.menu.illust_filter);
                }
            }

            @Override
            public void onPageScrollStateChanged(int i) {

            }
        });
    }

    @NonNull
    private Fragment getFragment(int index) {
        if (allPages[index] != null) {
            return allPages[index];
        }

        Fragment temp;
        if (index == 0) {
            temp = FragmentLikeIllust.newInstance(
                    sUserModel.getResponse().getUser().getId(),
                    Params.TYPE_PUBLUC
            );
        } else if (index == 1) {
            temp = FragmentLikeIllust.newInstance(
                    sUserModel.getResponse().getUser().getId(),
                    Params.TYPE_PRIVATE
            );
        } else if (index == 2) {
            temp = FragmentFollowUser.newInstance(
                    sUserModel.getResponse().getUser().getId(),
                    Params.TYPE_PUBLUC, false
            );
        } else if (index == 3) {
            temp = FragmentFollowUser.newInstance(
                    sUserModel.getResponse().getUser().getId(),
                    Params.TYPE_PRIVATE, false
            );
        } else if (index == 4) {
            temp = FragmentLikeNovel.newInstance(
                    sUserModel.getResponse().getUser().getId(),
                    Params.TYPE_PUBLUC, false
            );
        } else if (index == 5) {
            temp = FragmentLikeNovel.newInstance(
                    sUserModel.getResponse().getUser().getId(),
                    Params.TYPE_PRIVATE, false
            );
        } else {
            temp = new Fragment();
        }

        allPages[index] = temp;

        return allPages[index];
    }
}
