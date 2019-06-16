package ceui.lisa.activities;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringListener;
import com.facebook.rebound.SpringSystem;

import java.io.File;

import ceui.lisa.R;
import ceui.lisa.download.IllustDownload;
import ceui.lisa.fragments.FragmentImageDetail;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.Local;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class ImageDetailActivity extends BaseActivity {

    private IllustsBean mIllustsBean;
    private TextView currentPage, downloadSingle;

    @Override
    protected void initLayout() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        mLayoutID = R.layout.activity_image_detail;
    }

    @Override
    protected void initView() {
        ViewPager viewPager = findViewById(R.id.view_pager);
        currentPage = findViewById(R.id.current_page);
        downloadSingle = findViewById(R.id.download_this_one);
        mIllustsBean = (IllustsBean) getIntent().getSerializableExtra("illust");
        int index = getIntent().getIntExtra("index", 0);
        if(mIllustsBean == null){
            return;
        }
        viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                return FragmentImageDetail.newInstance(mIllustsBean, i);
            }

            @Override
            public int getCount() {
                return mIllustsBean.getPage_count();
            }
        });
        viewPager.setCurrentItem(index);
        downloadSingle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                IllustDownload.downloadIllust(mIllustsBean, viewPager.getCurrentItem());
            }
        });
        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
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
    }

    @Override
    protected void initData() {

    }
}
