package ceui.lisa.activities;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.BottomNavigationView;
import android.support.design.widget.NavigationView;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.GravityCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.DrawerLayout;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;


import com.bumptech.glide.Glide;

import org.greenrobot.eventbus.EventBus;

import java.util.List;

import ceui.lisa.R;
import ceui.lisa.fragments.BaseFragment;
import ceui.lisa.fragments.FragmentBlank;
import ceui.lisa.fragments.FragmentCenter;
import ceui.lisa.fragments.FragmentRight;
import ceui.lisa.fragments.FragmentLeft;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Local;
import ceui.lisa.response.UserModel;
import ceui.lisa.utils.Common;

public class CoverActivity extends BaseActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private ViewPager mViewPager;
    private DrawerLayout mDrawer;
    private ImageView userHead;
    private UserModel mUserModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUserModel = Local.getUser();
    }

    @Override
    protected void initLayout() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mLayoutID = R.layout.activity_cover;
    }

    @Override
    protected void initView() {
        mDrawer = findViewById(R.id.drawer_layout);
        mDrawer.setScrimColor(Color.TRANSPARENT);
        NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);
        userHead = navigationView.getHeaderView(0).findViewById(R.id.user_head);
        userHead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, UserDetailActivity.class);
                intent.putExtra("user id", mUserModel.getResponse().getUser().getId());
                startActivity(intent);
            }
        });
        BottomNavigationView bottomNavigationView = findViewById(R.id.navigation_view);
        bottomNavigationView.setOnNavigationItemSelectedListener(menuItem -> {
            if(menuItem.getItemId() == R.id.action_1){
                mViewPager.setCurrentItem(0);
                return true;
            }else if(menuItem.getItemId() == R.id.action_2){
                mViewPager.setCurrentItem(1);
                return true;
            }else if(menuItem.getItemId() == R.id.action_3){
                mViewPager.setCurrentItem(2);
                return true;
            }else {
                return false;
            }
        });
        mViewPager = findViewById(R.id.view_pager);
        mViewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int i, float v, int i1) {

            }

            @Override
            public void onPageSelected(int i) {
                bottomNavigationView.getMenu().getItem(i).setChecked(true);
            }

            @Override
            public void onPageScrollStateChanged(int i) {

            }
        });
    }


    private void initFragment(){
        BaseFragment[] baseFragments = new BaseFragment[]{
                new FragmentLeft(),
                new FragmentCenter(),
                new FragmentRight()
        };
        mViewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                return baseFragments[i];
            }

            @Override
            public int getCount() {
                return baseFragments.length;
            }
        });
    }


    @Override
    protected void initData() {
        UserModel userModel = Local.getUser();
        if(userModel != null && userModel.getResponse().getUser().isIs_login()){
            initFragment();
        }else {
            Common.showToast("未登录");
            Intent intent = new Intent(mContext, LoginActivity.class);
            startActivity(intent);
            finish();
        }
    }

    public DrawerLayout getDrawer() {
        return mDrawer;
    }

    @Override
    public void onBackPressed() {
        if (mDrawer.isDrawerOpen(GravityCompat.START)) {
            mDrawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }


    @SuppressWarnings("StatementWithEmptyBody")
    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();

        if (id == R.id.nav_camera) {

        } else if (id == R.id.nav_gallery) {

        } else if (id == R.id.nav_slideshow) {
            Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
            intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "浏览记录");
            startActivity(intent);
        } else if (id == R.id.nav_manage) {
            Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
            intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "设置");
            startActivity(intent);
        } else if (id == R.id.nav_share) {

        } else if (id == R.id.nav_send) {

        }

        mDrawer.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void onActivityReenter(int resultCode, Intent data) {
        super.onActivityReenter(resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Glide.with(mContext)
                .load(GlideUtil.getMediumImg(
                        mUserModel.getResponse().getUser().getProfile_image_urls().getMedium()))
                .into(userHead);
    }
}
