package ceui.lisa.activities;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.ToxicBakery.viewpager.transforms.CubeOutTransformer;
import com.blankj.utilcode.util.BarUtils;
import com.blankj.utilcode.util.ColorUtils;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivityImageDetailBinding;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.fragments.FragmentImageDetail;
import ceui.lisa.fragments.FragmentLocalImageDetail;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.utils.Common;

public class ImageDetailActivity extends BaseActivity<ActivityImageDetailBinding> {

    private IllustsBean mIllustsBean;
    private List<String> localIllust = new ArrayList<>();
    private TextView currentPage, downloadSingle, currentSize;
    private RelativeLayout mRvBottomRela;
    private int index;

    @Override
    protected int initLayout() {
        BarUtils.setStatusBarColor(this, ColorUtils.getColor(R.color.qmui_config_color_transparent));
        if (BarUtils.isSupportNavBar()) {
            BarUtils.setNavBarVisibility(this, false);
        }
        return R.layout.activity_image_detail;
    }

    @Override
    protected void initView() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        String dataType = getIntent().getStringExtra("dataType");
        baseBind.viewPager.setPageTransformer(true, new CubeOutTransformer());
        if (dataType.equals("二级详情")) {
            currentSize = findViewById(R.id.current_size);
            currentPage = findViewById(R.id.current_page);
            downloadSingle = findViewById(R.id.download_this_one);
            mRvBottomRela = findViewById(R.id.bottom_rela);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mRvBottomRela.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {

                    boolean changed;

                    @Override
                    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                        DisplayCutout displayCutout = v.getRootWindowInsets().getDisplayCutout();
                        if (displayCutout != null) {
                            if (!changed) {
                                changed = true;
                                Log.d("mRvBottomRela", "before " + v.getPaddingLeft() + " " + v.getPaddingTop() + " " + v.getPaddingRight() + " " + v.getPaddingBottom());
                                v.setPadding(v.getPaddingLeft() + displayCutout.getSafeInsetLeft(), v.getPaddingTop(), v.getPaddingRight() + displayCutout.getSafeInsetRight(), v.getPaddingBottom() + displayCutout.getSafeInsetBottom());
                                Log.d("mRvBottomRela", "after " + v.getPaddingLeft() + " " + v.getPaddingTop() + " " + v.getPaddingRight() + " " + v.getPaddingBottom());
                            }
                        }
                    }
                });
            }
            mIllustsBean = (IllustsBean) getIntent().getSerializableExtra("illust");
            index = getIntent().getIntExtra("index", 0);
            if (mIllustsBean == null) {
                return;
            }
            baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
                @Override
                public Fragment getItem(int i) {
                    return FragmentImageDetail.newInstance(mIllustsBean, i);
                }

                @Override
                public int getCount() {
                    return mIllustsBean.getPage_count();
                }
            });
            baseBind.viewPager.setCurrentItem(index);
            downloadSingle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    IllustDownload.downloadIllust(mActivity, mIllustsBean, baseBind.viewPager.getCurrentItem());
                }
            });
            baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int i, float v, int i1) {

                }

                @Override
                public void onPageSelected(int i) {
                    currentPage.setText("第" + (i + 1) + "P / 共" + mIllustsBean.getPage_count() + "P");
                }

                @Override
                public void onPageScrollStateChanged(int i) {

                }
            });
            currentPage.setText("第" + (index + 1) + "P / 共" + mIllustsBean.getPage_count() + "P");

        } else if (dataType.equals("下载详情")) {

            currentPage = findViewById(R.id.current_page);
            downloadSingle = findViewById(R.id.download_this_one);
            localIllust = (List<String>) getIntent().getSerializableExtra("illust");
            index = getIntent().getIntExtra("index", 0);

            baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
                @Override
                public Fragment getItem(int i) {
                    return FragmentLocalImageDetail.newInstance(localIllust.get(i));
                }

                @Override
                public int getCount() {
                    return localIllust.size();
                }
            });
            currentPage.setVisibility(View.GONE);
            baseBind.viewPager.setCurrentItem(index);
            baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                @Override
                public void onPageScrolled(int i, float v, int i1) {

                }

                @Override
                public void onPageSelected(int i) {
                    downloadSingle.setText("路径：" + localIllust.get(i));
                }

                @Override
                public void onPageScrollStateChanged(int i) {

                }
            });
            downloadSingle.setText("路径：" + localIllust.get(index));
        }
        currentPage.setTextAppearance(mContext, R.style.shadowText);
        downloadSingle.setTextAppearance(mContext, R.style.shadowText);
    }


    @Override
    protected void initData() {
        postponeEnterTransition();
    }

    @Override
    public void onBackPressed() {
        if(index == baseBind.viewPager.getCurrentItem()){
            Common.showLog(className + "没有滑动");
            super.onBackPressed();
        }else {
            Common.showLog(className + "滑动到其他页面不做动画");
            mActivity.finish();
        }
    }
}
