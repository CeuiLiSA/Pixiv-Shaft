package ceui.lisa.activities;

import android.Manifest;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.Nullable;
import android.support.design.widget.BottomNavigationView;
import android.support.design.widget.NavigationView;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.GravityCompat;
import android.support.v4.view.ViewPager;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.AlertDialog;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.tbruyelle.rxpermissions2.RxPermissions;
import com.zhihu.matisse.Matisse;
import com.zhihu.matisse.MimeType;
import com.zhihu.matisse.engine.impl.GlideEngine;
import com.zhihu.matisse.engine.impl.PicassoEngine;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.database.AppDatabase;
import ceui.lisa.download.FileCreator;
import ceui.lisa.download.TaskQueue;
import ceui.lisa.fragments.BaseFragment;
import ceui.lisa.fragments.FragmentCenter;
import ceui.lisa.fragments.FragmentRight;
import ceui.lisa.fragments.FragmentLeft;
import ceui.lisa.interfaces.Callback;
import ceui.lisa.utils.Channel;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.IllustChannel;
import ceui.lisa.utils.Local;
import ceui.lisa.model.UserModel;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.ReverseImage;
import ceui.lisa.utils.ReverseWebviewCallback;
import io.reactivex.disposables.Disposable;

import static android.Manifest.permission.READ_PHONE_STATE;
import static ceui.lisa.activities.PikaActivity.FILE_PATH;

import static ceui.lisa.activities.Shaft.sUserModel;
import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class CoverActivity extends BaseActivity
        implements NavigationView.OnNavigationItemSelectedListener {

    private static final int REQUEST_CODE_CHOOSE = 10086;
    private ViewPager mViewPager;
    private DrawerLayout mDrawer;
    private ImageView userHead, pikaBackground;
    private TextView username;
    private TextView user_email;
    private long mExitTime;

    @Override
    protected void initLayout() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mLayoutID = R.layout.activity_cover;
    }


    public void checkPermission(Callback<Object> callback){
        final RxPermissions rxPermissions = new RxPermissions((FragmentActivity) mActivity);
        Disposable disposable = rxPermissions
                .requestEachCombined(Manifest.permission.WRITE_EXTERNAL_STORAGE
//                        , Manifest.permission.ACCESS_COARSE_LOCATION,
//                        Manifest.permission.ACCESS_FINE_LOCATION,
//                        Manifest.permission.READ_PHONE_STATE
                )
                .subscribe(permission -> { // will emit 1 Permission object
                    if (permission.granted) {
                        callback.doSomething(null);
                    } else {
                        // At least one denied permission with ask never again
                        // Need to go to the settings
                        Common.showToast("请给与足够的权限");
                    }
                });
    }

    private void initContent(){
        mDrawer = findViewById(R.id.drawer_layout);
        mDrawer.setScrimColor(Color.TRANSPARENT);
        NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);
        userHead = navigationView.getHeaderView(0).findViewById(R.id.user_head);
        username = navigationView.getHeaderView(0).findViewById(R.id.user_name);
        user_email = navigationView.getHeaderView(0).findViewById(R.id.user_email);
        pikaBackground = navigationView.getHeaderView(0).findViewById(R.id.pika_bg);
        initDrawerHeader();
        userHead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(mContext, UserDetailActivity.class);
                intent.putExtra("user id", sUserModel.getResponse().getUser().getId());
                startActivity(intent);
            }
        });
        BottomNavigationView bottomNavigationView = findViewById(R.id.navigation_view);
        bottomNavigationView.setOnNavigationItemSelectedListener(menuItem -> {
            if (menuItem.getItemId() == R.id.action_1) {
                mViewPager.setCurrentItem(0);
                return true;
            } else if (menuItem.getItemId() == R.id.action_2) {
                mViewPager.setCurrentItem(1);
                return true;
            } else if (menuItem.getItemId() == R.id.action_3) {
                mViewPager.setCurrentItem(2);
                return true;
            } else {
                return false;
            }
        });
        mViewPager = findViewById(R.id.view_pager);
        mViewPager.setOffscreenPageLimit(3);
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

    @Override
    protected void initView() {
        initContent();
    }


    private void initFragment() {
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
        if (userModel != null && userModel.getResponse().getUser().isIs_login()) {
            checkPermission(t -> initFragment());
        } else {
            Intent intent = new Intent(mContext, LoginAlphaActivity.class);
            startActivity(intent);
            finish();
        }
    }

    public DrawerLayout getDrawer() {
        return mDrawer;
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();

        if (id == R.id.nav_camera) {
            Intent intent = new Intent(mContext, CollectionActivity.class);
            startActivity(intent);
        } else if (id == R.id.nav_gallery) {
            Intent intent = new Intent(mContext, DownloadManageActivity.class);
            startActivity(intent);
        } else if (id == R.id.nav_slideshow) {
            Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
            intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "浏览记录");
            startActivity(intent);
        } else if (id == R.id.nav_manage) {
            Intent intent = new Intent(mContext, TemplateFragmentActivity.class);
            intent.putExtra(TemplateFragmentActivity.EXTRA_FRAGMENT, "设置");
            startActivity(intent);
        } else if (id == R.id.nav_share) {


        } else if (id == R.id.nav_reverse) {
//            TODO remove

            Matisse.from((Activity) mContext)
                    .choose(MimeType.ofAll())// 选择 mime 的类型
                    .countable(true)
                    .maxSelectable(1) // 图片选择的最多数量
                    .restrictOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                    .thumbnailScale(1.0f) // 缩略图的比例
                    .imageEngine(new PicassoEngine()) // 使用的图片加载引擎
                    .forResult(REQUEST_CODE_CHOOSE);

        } else if (id == R.id.nav_send) {

            Intent intent = new Intent(mContext, FullscreenActivity.class);
            startActivity(intent);
        }

        mDrawer.closeDrawer(GravityCompat.START);
        return true;
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(Channel event) {
        if(className.contains(event.getReceiver())) {
            initDrawerHeader();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    protected void onStop() {
        EventBus.getDefault().unregister(this);
        super.onStop();
    }

    private void initDrawerHeader(){
        File file = new File(FILE_PATH, Local.getPikaImageFileName());
        if(file.exists()){
            Glide.with(mContext)
                    .load(file)
                    .placeholder(pikaBackground.getDrawable())
                    .transition(withCrossFade())
                    .into(pikaBackground);
            pikaBackground.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(mContext, ViewPagerActivity.class);
                    intent.putExtra("position", 0);
                    IllustChannel.get().setIllustList(Arrays.asList(Local.getPikaIllust()));
                    startActivity(intent);
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_CHOOSE && resultCode == RESULT_OK) {
            List<Uri> result = Matisse.obtainResult(data);
            if(result != null && result.size() != 0){
                ReverseImage.reverse(new File(Common.getRealFilePath(mContext, result.get(0))),
                        ReverseImage.ReverseProvider.SauceNao, new ReverseWebviewCallback(this));
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sUserModel != null && sUserModel.getResponse() != null) {
            Glide.with(mContext)
                    .load(GlideUtil.getMediumImg(
                            sUserModel.getResponse().getUser().getProfile_image_urls().getPx_170x170()))
                    .into(userHead);
            username.setText(sUserModel.getResponse().getUser().getName());
            user_email.setText(sUserModel.getResponse().getUser().getMail_address());
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mDrawer.isDrawerOpen(GravityCompat.START)) {
            mDrawer.closeDrawer(GravityCompat.START);
            return true;
        } else {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
                exit();
                return true;
            }
            return false;
        }
    }

    public void exit() {
        if ((System.currentTimeMillis() - mExitTime) > 2000) {

            if(TaskQueue.get().getTasks().size() != 0) {

                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setTitle("Shaft 提示");
                builder.setMessage("你还有未下载完成的任务，确定退出吗？");
                builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                });
                builder.setNegativeButton("取消", null);
                builder.setNeutralButton(getString(R.string.see_download_task), (dialog, which) -> {
                    Intent intent = new Intent(mContext, DownloadManageActivity.class);
                    startActivity(intent);
                });
                AlertDialog alertDialog = builder.create();
                alertDialog.show();
            } else {
                Common.showToast(getString(R.string.double_click_finish));
                mExitTime = System.currentTimeMillis();
            }


        } else {
            finish();
        }
    }
}
