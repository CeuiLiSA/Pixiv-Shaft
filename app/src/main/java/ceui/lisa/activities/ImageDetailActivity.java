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
import ceui.lisa.fragments.FragmentImageDetail;
import ceui.lisa.response.IllustsBean;
import ceui.lisa.utils.Local;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

public class ImageDetailActivity extends BaseActivity {

    private IllustsBean mIllustsBean;
    private ViewPager mViewPager;
    private int index;

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
        mViewPager = findViewById(R.id.view_pager);
        mIllustsBean = (IllustsBean) getIntent().getSerializableExtra("illust");
        index = getIntent().getIntExtra("index", 0);
        if(mIllustsBean == null){
            return;
        }

        mViewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
            @Override
            public Fragment getItem(int i) {
                return FragmentImageDetail.newInstance(mIllustsBean, i);
            }

            @Override
            public int getCount() {
                return mIllustsBean.getPage_count();
            }
        });
        mViewPager.setCurrentItem(index);
    }

    @Override
    protected void initData() {

    }
}
