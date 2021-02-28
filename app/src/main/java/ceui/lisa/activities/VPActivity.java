package ceui.lisa.activities;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;

import java.util.UUID;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivityMultiViewPagerTestBinding;
import ceui.lisa.feature.ScaleTrans;
import ceui.lisa.fragments.TestFragment;
import ceui.lisa.utils.Common;

public class VPActivity extends BaseActivity<ActivityMultiViewPagerTestBinding> {

    private SpringSystem mSpringSystem;
    private Spring x, y;
    private String uuid;

    @Override
    protected int initLayout() {
        return R.layout.activity_multi_view_pager_test;
    }

    @Override
    protected void initView() {
        final String[] CHINESE_TITLES = new String[]{
                mContext.getString(R.string.daily_rank),
                mContext.getString(R.string.weekly_rank),
                mContext.getString(R.string.monthly_rank),
                mContext.getString(R.string.man_like),
                mContext.getString(R.string.woman_like),
                mContext.getString(R.string.self_done),
                mContext.getString(R.string.new_fish),
                mContext.getString(R.string.r_eighteen)
        };
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
            @NonNull
            @Override
            public Fragment getItem(int position) {
                return TestFragment.newInstance(position);
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
        baseBind.viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            }

            @Override
            public void onPageSelected(int position) {
//                Common.showLog("设置了一个 " + baseBind.viewPager.findViewWithTag());
            }

            @Override
            public void onPageScrollStateChanged(int state) {

            }
        });
        mSpringSystem = SpringSystem.create();
        x = mSpringSystem.createSpring();
        y = mSpringSystem.createSpring();
        x.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(20, 5));
        y.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(20, 5));
        x.addListener(new SimpleSpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                baseBind.viewPager.setScaleX((float) spring.getCurrentValue());
            }
        });
        y.addListener(new SimpleSpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                baseBind.viewPager.setScaleY((float) spring.getCurrentValue());
            }

            @Override
            public void onSpringAtRest(Spring spring) {
                baseBind.viewPagerSmall.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager()) {
                    @NonNull
                    @Override
                    public Fragment getItem(int position) {
                        return TestFragment.newInstance(position);
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
                baseBind.viewPagerSmall.setPageTransformer(false, new ScaleTrans());
                baseBind.viewPager.setVisibility(View.INVISIBLE);
            }
        });
        baseBind.viewPager.setOffscreenPageLimit(3);
        baseBind.scale.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (int i = 0; i < baseBind.viewPager.getChildCount(); i++) {
                    if (TextUtils.equals(uuid, (String) baseBind.viewPager.getChildAt(i).getTag())) {
                        baseBind.viewPager.getChildAt(i).setVisibility(View.VISIBLE);
                    } else {
                        baseBind.viewPager.getChildAt(i).setVisibility(View.INVISIBLE);
                    }
                }

                final float size = 0.8f;
                x.setCurrentValue(1.0f);
                y.setCurrentValue(1.0f);

                x.setEndValue(size);
                y.setEndValue(size);
            }
        });
    }

    @Override
    protected void initData() {

    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }
}
