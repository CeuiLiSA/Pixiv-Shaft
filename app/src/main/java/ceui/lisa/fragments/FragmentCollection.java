package ceui.lisa.fragments;

import android.content.Intent;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.ToxicBakery.viewpager.transforms.DrawerTransformer;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.utils.Common;

import static ceui.lisa.activities.Shaft.sUserModel;

public class FragmentCollection extends BaseBindFragment<ViewpagerWithTablayoutBinding> {

    private static final String[] CHINESE_TITLES = new String[]{
            Shaft.getContext().getString(R.string.public_like_illust),
            Shaft.getContext().getString(R.string.private_like_illust),
            Shaft.getContext().getString(R.string.public_like_user),
            Shaft.getContext().getString(R.string.private_like_user)};
    private Fragment[] allPages;


    @Override
    void initLayout() {
        mLayoutID = R.layout.viewpager_with_tablayout;
    }

    @Override
    public void initView(View view) {
        baseBind.toolbar.setTitle(mContext.getString(R.string.bookmark));
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (baseBind.viewPager.getCurrentItem() == 0) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_KEYWORD,
                            FragmentLikeIllust.TYPE_PUBLUC);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT,
                            mContext.getString(R.string.filter_by_bookmark));
                    startActivity(intent);
                    return true;
                } else if (baseBind.viewPager.getCurrentItem() == 1) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_KEYWORD,
                            FragmentLikeIllust.TYPE_PRIVATE);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT,
                            mContext.getString(R.string.filter_by_bookmark));
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });
        baseBind.viewPager.setPageTransformer(true, new DrawerTransformer());
        allPages = new Fragment[]{
                FragmentLikeIllust.newInstance(sUserModel.getResponse().getUser().getId(),
                        FragmentLikeIllust.TYPE_PUBLUC),
                FragmentLikeIllust.newInstance(sUserModel.getResponse().getUser().getId(),
                        FragmentLikeIllust.TYPE_PRIVATE),
                FragmentFollowUser.newInstance(sUserModel.getResponse().getUser().getId(),
                        FragmentLikeIllust.TYPE_PUBLUC, false),
                FragmentFollowUser.newInstance(sUserModel.getResponse().getUser().getId(),
                        FragmentLikeIllust.TYPE_PRIVATE, false)
        };
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
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
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        baseBind.toolbar.inflateMenu(R.menu.illust_filter);
        baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i1) {

            }

            @Override
            public void onPageSelected(int i) {
                baseBind.toolbar.getMenu().clear();
                if(i == 0){
                    Common.showLog("添加menu");
                    baseBind.toolbar.inflateMenu(R.menu.illust_filter);
                } else if(i == 1){
                    Common.showLog("添加menu");
                    baseBind.toolbar.inflateMenu(R.menu.illust_filter);
                }
            }

            @Override
            public void onPageScrollStateChanged(int i) {

            }
        });
    }

    @Override
    void initData() {

    }
}
