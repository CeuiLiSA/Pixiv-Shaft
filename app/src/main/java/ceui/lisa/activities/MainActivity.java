package ceui.lisa.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.viewpager.widget.ViewPager;

import com.blankj.utilcode.util.LanguageUtils;
import com.blankj.utilcode.util.UriUtils;
import com.bumptech.glide.Glide;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;
import com.qmuiteam.qmui.widget.dialog.QMUIDialog;
import com.tbruyelle.rxpermissions3.RxPermissions;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

import ceui.lisa.R;
import ceui.lisa.core.Manager;
import ceui.lisa.databinding.ActivityCoverBinding;
import ceui.lisa.fragments.FragmentCenter;
import ceui.lisa.fragments.FragmentLeft;
import ceui.lisa.fragments.FragmentRight;
import ceui.lisa.http.Retro;
import ceui.lisa.notification.BaseReceiver;
import ceui.lisa.notification.CallBackReceiver;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.ReverseImage;
import ceui.lisa.utils.ReverseWebviewCallback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static ceui.lisa.activities.Shaft.sUserModel;
import static ceui.lisa.utils.Settings.ALL_LANGUAGE;

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
        return true;
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
            }
        });
        baseBind.navigationView.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                if (item.getItemId() == R.id.action_1) {
                    for (int i = 0; i < baseFragments.length; i++) {
                        if (baseFragments[i] instanceof FragmentLeft) {
                            baseBind.viewPager.setCurrentItem(i);
                            return true;
                        }
                    }
                } else if (item.getItemId() == R.id.action_2) {
                    for (int i = 0; i < baseFragments.length; i++) {
                        if (baseFragments[i] instanceof FragmentCenter) {
                            baseBind.viewPager.setCurrentItem(i);
                            return true;
                        }
                    }
                } else if (item.getItemId() == R.id.action_3) {
                    for (int i = 0; i < baseFragments.length; i++) {
                        if (baseFragments[i] instanceof FragmentRight) {
                            baseBind.viewPager.setCurrentItem(i);
                            return true;
                        }
                    }
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
                            ((FragmentLeft) baseFragment).scrollToTop();
                        }
                    }
                } else if (item.getItemId() == R.id.action_2) {
                    for (Fragment baseFragment : baseFragments) {
                        if (baseFragment instanceof FragmentCenter) {
                            ((FragmentCenter) baseFragment).lazyData();
                        }
                    }
                } else if (item.getItemId() == R.id.action_3) {
                    for (Fragment baseFragment : baseFragments) {
                        if (baseFragment instanceof FragmentRight) {
                            ((FragmentRight) baseFragment).scrollToTop();
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
                if (baseFragments[position] instanceof FragmentLeft) {
                    baseBind.navigationView.setSelectedItemId(R.id.action_1);
                } else if (baseFragments[position] instanceof FragmentCenter) {
                    baseBind.navigationView.setSelectedItemId(R.id.action_2);
                } else if (baseFragments[position] instanceof FragmentRight) {
                    baseBind.navigationView.setSelectedItemId(R.id.action_3);
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {

            }
        });
    }

    private void initFragment() {
        int order = Shaft.sSettings.getBottomBarOrder();
        baseBind.navigationView.getMenu().clear();
        switch (order) {
            case 0:
                baseBind.navigationView.inflateMenu(R.menu.main_activity0);
                baseFragments = new Fragment[]{
                        new FragmentLeft(),
                        new FragmentCenter(),
                        new FragmentRight()
                };
                break;
            case 1:
                baseBind.navigationView.inflateMenu(R.menu.main_activity1);
                baseFragments = new Fragment[]{
                        new FragmentLeft(),
                        new FragmentRight(),
                        new FragmentCenter()
                };
                break;
            case 2:
                baseBind.navigationView.inflateMenu(R.menu.main_activity2);
                baseFragments = new Fragment[]{
                        new FragmentCenter(),
                        new FragmentLeft(),
                        new FragmentRight()
                };
                break;
            case 3:
                baseBind.navigationView.inflateMenu(R.menu.main_activity3);
                baseFragments = new Fragment[]{
                        new FragmentCenter(),
                        new FragmentRight(),
                        new FragmentLeft()
                };
                break;
            case 4:
                baseBind.navigationView.inflateMenu(R.menu.main_activity4);
                baseFragments = new Fragment[]{
                        new FragmentRight(),
                        new FragmentLeft(),
                        new FragmentCenter(),
                };
                break;
            case 5:
                baseBind.navigationView.inflateMenu(R.menu.main_activity5);
                baseFragments = new Fragment[]{
                        new FragmentRight(),
                        new FragmentCenter(),
                        new FragmentLeft(),
                };
                break;
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
    }

    @Override
    protected void initData() {
        if (sUserModel != null && sUserModel.getResponse().getUser().isIs_login()) {
            if (Common.isAndroidQ()) {
                initFragment();
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
            Manager.get().restore(mContext);
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
                intent.putExtra(Params.USER_ID, sUserModel.getResponse().getUser().getId());
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
        if (sUserModel != null && sUserModel.getResponse() != null) {
            Glide.with(mContext)
                    .load(GlideUtil.getHead(sUserModel.getResponse().getUser()))
                    .into(userHead);
            username.setText(sUserModel.getResponse().getUser().getName());
            user_email.setText(TextUtils.isEmpty(sUserModel.getResponse().getUser().getMail_address()) ?
                    mContext.getString(R.string.no_mail_address) : sUserModel.getResponse().getUser().getMail_address());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Params.REQUEST_CODE_CHOOSE && resultCode == RESULT_OK) {
            try {
                ReverseImage.reverse(UriUtils.uri2Bytes(data.getData()),
                        ReverseImage.ReverseProvider.SauceNao, new ReverseWebviewCallback(this));
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
                        Manager.get().stop();
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
        getUrl();
    }

    private void getUrl() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                OkHttpClient client = Retro.getLogClient().build();

                Request request = new Request.Builder()
                        .url("http://45.32.252.225:8000/user/kkkkk")
                        .build();

                try {
                    Response response = client.newCall(request).execute();
                    String result = response.body().string();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
}
