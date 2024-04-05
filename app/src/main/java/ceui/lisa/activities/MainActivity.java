package ceui.lisa.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.tbruyelle.rxpermissions3.RxPermissions;

import java.io.File;

import ceui.lisa.R;
import ceui.lisa.core.Manager;
import ceui.lisa.databinding.ActivityCoverBinding;
import ceui.lisa.fragments.FragmentCenter;
import ceui.lisa.fragments.FragmentLeft;
import ceui.lisa.fragments.FragmentRight;
import ceui.lisa.fragments.FragmentViewPager;
import ceui.lisa.helper.DrawerLayoutHelper;
import ceui.lisa.helper.NavigationLocationHelper;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.ReverseImage;
import ceui.lisa.utils.ReverseWebviewCallback;
import ceui.lisa.view.DrawerLayoutViewPager;

import static ceui.lisa.activities.Shaft.sUserModel;

/**
 * 主页
 */
public class MainActivity extends BaseActivity<ActivityCoverBinding>
        implements NavigationView.OnNavigationItemSelectedListener {

    private ImageView userHead;
    private TextView username;
    private TextView user_email;
    private long mExitTime;
    private Fragment[] baseFragments = null;

    @Override
    protected int initLayout() {
        return R.layout.activity_cover;
    }

    @Override
    public boolean hideStatusBar() {
        return Dev.hideMainActivityStatus;
    }

    @Override
    protected void initView() {
        Dev.isDev = Shaft.getMMKV().decodeBool(Params.USE_DEBUG, false);
        baseBind.drawerLayout.setScrimColor(Color.TRANSPARENT);
        baseBind.navView.setNavigationItemSelectedListener(this);
        userHead = baseBind.navView.getHeaderView(0).findViewById(R.id.user_head);
        username = baseBind.navView.getHeaderView(0).findViewById(R.id.user_name);
        user_email = baseBind.navView.getHeaderView(0).findViewById(R.id.user_email);
        initDrawerHeader();
        userHead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Common.showUser(mContext, sUserModel);
                baseBind.drawerLayout.closeDrawer(GravityCompat.START);
            }
        });
        userHead.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                boolean filterEnable = Shaft.sSettings.isR18FilterTempEnable();
                Shaft.sSettings.setR18FilterTempEnable(!filterEnable);
                Common.showToast(filterEnable ? "ԅ(♡﹃♡ԅ)" : "X﹏X");
                return true;
            }
        });
        baseBind.navigationView.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                if (item.getItemId() == R.id.action_1) {
                    baseBind.viewPager.setCurrentItem(0);
                    return true;
                } else if (item.getItemId() == R.id.action_2) {
                    baseBind.viewPager.setCurrentItem(1);
                    return true;
                } else if (item.getItemId() == R.id.action_3) {
                    baseBind.viewPager.setCurrentItem(2);
                    return true;
                } else if (item.getItemId() == R.id.action_4) {
                    baseBind.viewPager.setCurrentItem(3);
                    return true;
                }
                return false;
            }
        });
        baseBind.navigationView.setOnNavigationItemReselectedListener(new BottomNavigationView.OnNavigationItemReselectedListener() {
            @Override
            public void onNavigationItemReselected(@NonNull MenuItem item) {
                if (item.getItemId() == R.id.action_1) {
                    for (Fragment baseFragment : baseFragments) {
                        if (baseFragment instanceof FragmentLeft) {
                            ((FragmentLeft) baseFragment).forceRefresh();
                        }
                    }
                } else if (item.getItemId() == R.id.action_2) {
                    for (Fragment baseFragment : baseFragments) {
                        if (baseFragment instanceof FragmentCenter) {
                            ((FragmentCenter) baseFragment).forceRefresh();
                        }
                    }
                } else if (item.getItemId() == R.id.action_3) {
                    for (Fragment baseFragment : baseFragments) {
                        if (baseFragment instanceof FragmentRight) {
                            ((FragmentRight) baseFragment).forceRefresh();
                        }
                    }
                } else if (item.getItemId() == R.id.action_4) {
                    for (Fragment baseFragment : baseFragments) {
                        if (baseFragment instanceof FragmentViewPager) {
                            ((FragmentViewPager) baseFragment).forceRefresh();
                        }
                    }
                }
            }
        });
        baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

            }

            @Override
            public void onPageSelected(int position) {
                if (position == 0) {
                    baseBind.navigationView.setSelectedItemId(R.id.action_1);
                } else if (position == 1) {
                    baseBind.navigationView.setSelectedItemId(R.id.action_2);
                } else if (position == 2) {
                    baseBind.navigationView.setSelectedItemId(R.id.action_3);
                } else if (position == 3) {
                    baseBind.navigationView.setSelectedItemId(R.id.action_4);
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {

            }
        });

        baseBind.viewPager.setTouchEventForwarder(new DrawerLayoutViewPager.IForwardTouchEvent() {
            @Override
            public void forwardTouchEvent(MotionEvent ev) {
                getDrawer().onTouchEvent(ev);
            }
        });
        DrawerLayoutHelper.setCustomLeftEdgeSize(getDrawer(), 1.0f);
    }

    private void initFragment() {
        if (Shaft.sSettings.isMainViewR18()) {
            baseBind.navigationView.inflateMenu(R.menu.main_activity0_with_r18);
            baseFragments = new Fragment[]{
                    new FragmentLeft(),
                    new FragmentCenter(),
                    new FragmentRight(),
                    FragmentViewPager.newInstance(Params.VIEW_PAGER_R18),
            };
        } else {
            baseBind.navigationView.inflateMenu(R.menu.main_activity0);
            baseFragments = new Fragment[]{
                    new FragmentLeft(),
                    new FragmentCenter(),
                    new FragmentRight()
            };
        }
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                return baseFragments[i];
            }

            @Override
            public int getCount() {
                return baseFragments.length;
            }
        });
        baseBind.viewPager.setOffscreenPageLimit(baseFragments.length - 1);
        baseBind.viewPager.setCurrentItem(getNavigationInitPosition());
        Manager.get().restore();
    }

    @Override
    protected void initData() {
        if (sUserModel != null && sUserModel.getUser() != null && sUserModel.getUser().isIs_login()) {
            if (Common.isAndroidQ()) {
                initFragment();
//                startActivity(new Intent(this, ListActivity.class));
            } else {
                new RxPermissions(mActivity)
                        .requestEachCombined(
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                        .subscribe(permission -> {
                            if (permission.granted) {
                                initFragment();
                            } else {
                                Common.showToast(mActivity.getString(R.string.access_denied));
                                finish();
                            }
                        });
            }
        } else {
            Intent intent = new Intent(mContext, TemplateActivity.class);
            intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "登录注册");
            startActivity(intent);
            finish();
        }
    }

    public DrawerLayout getDrawer() {
        return baseBind.drawerLayout;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();

        Intent intent = null;
        switch (id) {
            case R.id.nav_gallery:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "下载管理");
                intent.putExtra("hideStatusBar", false);
                break;
            case R.id.nav_slideshow:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "浏览记录");
                break;
            case R.id.nav_manage:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "设置");
                break;
            case R.id.nav_share:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "关于软件");
                break;
            case R.id.main_page:
                intent = new Intent(mContext, UserActivity.class);
                intent.putExtra(Params.USER_ID, sUserModel.getUser().getId());
                break;
            case R.id.nav_reverse:
                selectPhoto();
                break;
            case R.id.nav_new_work:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "最新作品");
                intent.putExtra("hideStatusBar", false);
                break;
            case R.id.muted_list:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "标签屏蔽记录");
                break;
            case R.id.nav_feature:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "精华列");
                break;
            case R.id.nav_fans:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "粉丝");
                break;
            case R.id.illust_star:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "我的插画收藏");
                intent.putExtra("hideStatusBar", false);
                break;
            case R.id.novel_star:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "我的小说收藏");
                intent.putExtra("hideStatusBar", false);
                break;
            case R.id.follow_user:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "我的关注");
                intent.putExtra("hideStatusBar", false);
                break;
            case R.id.new_work:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "网页链接");
                intent.putExtra(Params.URL, "https://www.pixiv.net/upload.php");
                intent.putExtra(Params.TITLE, getString(R.string.string_444));
                intent.putExtra(Params.PREFER_PRESERVE, true);
                break;
            default:
                break;
        }
        if (intent != null) {
            startActivity(intent);
        }

        baseBind.drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.clear();
    }

    public static final String[] ALL_SELECT_WAY = new String[]{"图库选图", "文件管理器选图"};

    private void selectPhoto() {
        new QMUIDialog.CheckableDialogBuilder(mActivity)
                .addItems(ALL_SELECT_WAY, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            Intent intentToPickPic = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                            intentToPickPic.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
                            startActivityForResult(intentToPickPic, Params.REQUEST_CODE_CHOOSE);
                        } else {
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);//必须
                            intent.setType("image/*");//必须
                            startActivityForResult(intent, Params.REQUEST_CODE_CHOOSE);
                        }
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void initDrawerHeader() {
        if (sUserModel != null && sUserModel.getUser() != null) {
            Glide.with(mContext)
                    .load(GlideUtil.getHead(sUserModel.getUser()))
                    .into(userHead);
            username.setText(sUserModel.getUser().getName());
            user_email.setText(TextUtils.isEmpty(sUserModel.getUser().getMail_address()) ?
                    mContext.getString(R.string.no_mail_address) : sUserModel.getUser().getMail_address());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Params.REQUEST_CODE_CHOOSE && resultCode == RESULT_OK) {
            try {
                Uri imageUri = data.getData();
                File innerImageFile = Common.copyUriToImageCacheFolder(imageUri);
                Uri innerImageFileUri = Uri.fromFile(innerImageFile);
                if (!ReverseImage.isFileSizeOkToSearch(imageUri, ReverseImage.DEFAULT_ENGINE)) {
                    Common.showToast(getString(R.string.string_410));
                    return;
                }
                ReverseImage.reverse(innerImageFileUri,
                        ReverseImage.DEFAULT_ENGINE, new ReverseWebviewCallback(this, innerImageFileUri));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (baseBind.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            baseBind.drawerLayout.closeDrawer(GravityCompat.START);
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
            if (Manager.get().getContent().size() != 0) {
                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setTitle(getString(R.string.shaft_hint));
                builder.setMessage(mContext.getString(R.string.you_have_download_plan));
                builder.setPositiveButton(mContext.getString(R.string.sure), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Manager.get().stopAll();
                        finish();
                    }
                });
                builder.setNegativeButton(mContext.getString(R.string.cancel), null);
                builder.setNeutralButton(getString(R.string.see_download_task), (dialog, which) -> {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "下载管理");
                    intent.putExtra("hideStatusBar", true);
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

    @Override
    protected void onResume() {
        super.onResume();
        if (Dev.refreshUser) {
            initDrawerHeader();
            Dev.refreshUser = false;
        }
    }

    @Override
    public void finish() {
        int currentPosition = baseBind.viewPager.getCurrentItem();
        Shaft.getMMKV().putInt(Params.MAIN_ACTIVITY_NAVIGATION_POSITION, currentPosition);
        super.finish();
    }

    private int getNavigationInitPosition() {
        int defaultPosition = 0;
        String settingValue = Shaft.sSettings.getNavigationInitPosition();
        if (settingValue.equals(NavigationLocationHelper.LATEST)) {
            int latestPosition = Shaft.getMMKV().getInt(Params.MAIN_ACTIVITY_NAVIGATION_POSITION, 0);
            return latestPosition < baseFragments.length ? latestPosition : defaultPosition;
        }
        NavigationLocationHelper.NavigationItem navigationValue = NavigationLocationHelper.NAVIGATION_MAP.getOrDefault(settingValue, null);
        if (navigationValue == null) {
            return defaultPosition;
        }
        Class clazz = navigationValue.getInstanceClass();
        for (int i = 0; i < baseFragments.length; i++) {
            Fragment fragment = baseFragments[i];
            if (clazz == fragment.getClass()) {
                return i;
            }
        }
        return defaultPosition;
    }
}
