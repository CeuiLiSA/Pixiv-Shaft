package ceui.lisa.activities;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import com.bumptech.glide.Glide;
import com.google.android.material.navigation.NavigationView;
import com.tbruyelle.rxpermissions2.RxPermissions;

import java.io.File;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivityCoverBinding;
import ceui.lisa.download.TaskQueue;
import ceui.lisa.fragments.BaseFragment;
import ceui.lisa.fragments.FragmentCenter;
import ceui.lisa.fragments.FragmentLeft;
import ceui.lisa.fragments.FragmentRight;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.utils.Local;
import ceui.lisa.utils.Params;
import ceui.lisa.utils.ReverseImage;
import ceui.lisa.utils.ReverseWebviewCallback;
import io.reactivex.disposables.Disposable;

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
    private BaseFragment<?>[] baseFragments = null;

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
        Dev.isDev = Local.getBoolean(Params.USE_DEBUG, false);
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
        baseBind.viewPager.setOffscreenPageLimit(3);
    }

    private void initFragment() {
        baseFragments = new BaseFragment[]{
                new FragmentLeft(),
                new FragmentCenter(),
                new FragmentRight()
        };
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
        baseBind.navigationView.setupWithViewPager(baseBind.viewPager);
    }

    @Override
    protected void initData() {
        if (sUserModel != null && sUserModel.getResponse().getUser().isIs_login()) {
            final RxPermissions rxPermissions = new RxPermissions(mActivity);
            Disposable disposable = rxPermissions
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

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();

        Intent intent = null;
        switch (id) {
            case R.id.nav_camera:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "收藏夹");
                intent.putExtra("hideStatusBar", false);
                break;
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
                // TODO: 20-3-16 国际化 向用户索要权限
                if (Shaft.sSettings.isReverseDialogNeverShowAgain()) {
                    selectPhoto();
                } else {
                    CheckBox checkBox = new CheckBox(this);
                    checkBox.setText(R.string.never_show_again);
                    checkBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                            Shaft.sSettings.setReverseDialogNeverShowAgain(isChecked);
                            Local.setSettings(Shaft.sSettings);
                        }
                    });
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("关于以图搜源")
                            .setMessage("以图搜源的实质是将你选择的图片上传至 https://saucenao.com/ 进行搜索\n" +
                                    "https://saucenao.com/ 可以算一个专门查找P站图的网站，更多信息不在这里介绍\n" +
                                    "注意：该功能需要 READ_EXTERNAL_STORAGE 以读取图片，如果 SDK >= 23 且没有授权" +
                                    "则功能无法实现")
                            .setView(checkBox)
                            .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    String[] permissions = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
                                    int i = ContextCompat.checkSelfPermission(this, permissions[0]);
                                    if (i != PackageManager.PERMISSION_GRANTED) {
                                        ActivityCompat.requestPermissions(this, permissions, 1);
                                    } else {
                                        selectPhoto();
                                    }
                                } else {
                                    selectPhoto();
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }
                break;
            case R.id.nav_send:
                intent = new Intent(mContext, TemplateActivity.class);
                intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "画廊");
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
        }
        if (intent != null) {
            startActivity(intent);
        }

        baseBind.drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        //super.onSaveInstanceState(outState);
    }

    private void selectPhoto() {
        Intent intentToPickPic = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intentToPickPic.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        startActivityForResult(intentToPickPic, Params.REQUEST_CODE_CHOOSE);
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
            Uri imageUri = data.getData();
            ReverseImage.reverse(new File(Common.getRealFilePath(mContext, imageUri)),
                    ReverseImage.ReverseProvider.SauceNao, new ReverseWebviewCallback(this));
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
            if (TaskQueue.get().getTasks().size() != 0) {
                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setTitle(getString(R.string.shaft_hint));
                builder.setMessage(mContext.getString(R.string.you_have_download_plan));
                builder.setPositiveButton(mContext.getString(R.string.sure), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        TaskQueue.get().clearTask();
                        finish();
                    }
                });
                builder.setNegativeButton(mContext.getString(R.string.cancel), null);
                builder.setNeutralButton(getString(R.string.see_download_task), (dialog, which) -> {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "下载管理");
                    intent.putExtra("hideStatusBar", false);
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
//        Container.get().clear();
    }
}
