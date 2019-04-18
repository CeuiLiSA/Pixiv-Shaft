package ceui.lisa.activities;

import android.content.Intent;
import android.graphics.Color;
import android.support.annotation.NonNull;
import android.support.design.widget.BottomNavigationView;
import android.support.design.widget.NavigationView;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.GravityCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.DrawerLayout;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import java.util.List;
import java.util.Random;

import ceui.lisa.R;
import ceui.lisa.fragments.BaseFragment;
import ceui.lisa.fragments.FragmentBlank;
import ceui.lisa.fragments.FragmentHotTag;
import ceui.lisa.fragments.FragmentRecmd;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.Local;
import ceui.lisa.response.UserModel;
import ceui.lisa.utils.Common;

public class CoverActivity extends BaseActivity {

    private ViewPager mViewPager;

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
                new FragmentHotTag(),
                new FragmentRecmd(),
                new FragmentBlank()
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
//            Intent intent = new Intent(mContext, BlankActivity.class);
//            startActivity(intent);
//            finish();
            initFragment();
        }else {
            Common.showToast("未登录");
            Intent intent = new Intent(mContext, LoginActivity.class);
            startActivity(intent);
            finish();
        }
    }
}
