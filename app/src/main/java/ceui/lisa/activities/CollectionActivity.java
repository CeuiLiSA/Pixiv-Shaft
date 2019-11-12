package ceui.lisa.activities;

import android.content.Intent;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.ToxicBakery.viewpager.transforms.DrawerTransformer;
import com.google.android.material.tabs.TabLayout;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivityDownloadManageBinding;
import ceui.lisa.fragments.FragmentFollowUser;
import ceui.lisa.fragments.FragmentLikeIllust;

import static ceui.lisa.activities.Shaft.sUserModel;

public class CollectionActivity extends BaseActivity<ActivityDownloadManageBinding> {

    private static final String[] CHINESE_TITLES = new String[]{
            Shaft.getContext().getString(R.string.public_like_illust), 
            Shaft.getContext().getString(R.string.private_like_illust), 
            Shaft.getContext().getString(R.string.public_like_user), 
            Shaft.getContext().getString(R.string.private_like_user)};
    private Fragment[] allPages;

    @Override
    protected int initLayout() {
        return R.layout.activity_download_manage;
    }

    @Override
    public void initView() {
        setSupportActionBar(baseBind.toolbar);
        baseBind.toolbar.setTitle(mContext.getString(R.string.bookmark));
        baseBind.toolbar.setNavigationOnClickListener(v -> finish());
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
        baseBind.viewPager.setAdapter(new FragmentStatePagerAdapter(getSupportFragmentManager()) {
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
        baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i1) {

            }

            @Override
            public void onPageSelected(int i) {
                invalidateOptionsMenu();
            }

            @Override
            public void onPageScrollStateChanged(int i) {

            }
        });
    }

    @Override
    public void initData() {

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (baseBind.viewPager != null) {
            if (baseBind.viewPager.getCurrentItem() == 0 ||
                    baseBind.viewPager.getCurrentItem() == 1) {
                getMenuInflater().inflate(R.menu.illust_filter, menu);
                return true;
            } else {
                return super.onCreateOptionsMenu(menu);
            }
        } else {
            return super.onCreateOptionsMenu(menu);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (baseBind.viewPager.getCurrentItem() == 0) {
            Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
            intent.putExtra(TemplateFragmentActivity.EXTRA_KEYWORD,
                    FragmentLikeIllust.TYPE_PUBLUC);
            intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT,
                    mContext.getString(R.string.filter_by_bookmark));
            startActivity(intent);
        } else if (baseBind.viewPager.getCurrentItem() == 1) {
            Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
            intent.putExtra(TemplateFragmentActivity.EXTRA_KEYWORD,
                    FragmentLikeIllust.TYPE_PRIVATE);
            intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT,
                    mContext.getString(R.string.filter_by_bookmark));
            startActivity(intent);
        }
        return super.onOptionsItemSelected(item);
    }
}
