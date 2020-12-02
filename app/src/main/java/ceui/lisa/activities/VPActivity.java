package ceui.lisa.activities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.lifecycle.ViewModelProvider;

import ceui.lisa.R;
import ceui.lisa.databinding.ActivityMultiViewPagerBinding;
import ceui.lisa.fragments.TestFragment;
import ceui.lisa.viewmodel.VPModel;

public class VPActivity extends BaseActivity<ActivityMultiViewPagerBinding> {

    private VPModel mVPModel;

    @Override
    protected int initLayout() {
        return R.layout.activity_multi_view_pager;
    }

    @Override
    public void initModel() {
        mVPModel = new ViewModelProvider(this).get(VPModel.class);
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
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
    }

    @Override
    protected void initData() {

    }
}
