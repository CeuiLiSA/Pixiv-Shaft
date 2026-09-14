package ceui.lisa.fragments;

import android.content.Intent;
import android.view.MenuItem;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import ceui.lisa.R;
import ceui.lisa.BuildConfig;
import ceui.pixiv.ui.recommend.DailyRecommendationsFragment;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.utils.SystemBarMetrics;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentLeftBinding;
import ceui.lisa.utils.MyOnTabSelectedListener;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Params;
import ceui.pixiv.feeds.FeedFragment;
import ceui.pixiv.ui.home.RecmdIllustFeedFragment;
import ceui.pixiv.ui.trending.HotTagsFeedFragment;
import ceui.pixiv.ui.navigation.TemplateRoute;

public class FragmentLeft extends BaseLazyFragment<FragmentLeftBinding> {

    private Fragment[] mFragments = null;

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_left;
    }

    @Override
    public void initView() {
        if (Dev.hideMainActivityStatus) {
            ViewGroup.LayoutParams headParams = baseBind.head.getLayoutParams();
            headParams.height = SystemBarMetrics.statusBarHeight(mContext);
            baseBind.head.setLayoutParams(headParams);
        }

        baseBind.toolbar.setNavigationOnClickListener(v -> {
            if (mActivity instanceof MainActivity) {
                ((MainActivity) mActivity).getDrawer().openDrawer(GravityCompat.START, true);
            }
        });
        baseBind.toolbarTitle.setText(R.string.string_207);
        baseBind.toolbar.inflateMenu(R.menu.fragment_left);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_search) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SEARCH.key);
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public void lazyData() {
        final boolean hotTagsFirst = Shaft.sSettings.isRecommendHotTagsFirst();
        final String[] TITLES = BuildConfig.IS_LITE ? new String[]{
                Shaft.getContext().getString(R.string.recommend_illust),
                Shaft.getContext().getString(R.string.hot_tag)
        } : new String[]{
                getString(R.string.recommend_illust),
                getString(R.string.hot_tag),
                getString(R.string.daily_recommendations)
        };
        mFragments = BuildConfig.IS_LITE ? new Fragment[]{
                RecmdIllustFeedFragment.newInstance(RecmdIllustFeedFragment.TYPE_ILLUST),
                HotTagsFeedFragment.newInstance(Params.TYPE_ILLUST)
        } : new Fragment[]{
                RecmdIllustFeedFragment.newInstance(RecmdIllustFeedFragment.TYPE_ILLUST),
                HotTagsFeedFragment.newInstance(Params.TYPE_ILLUST),
                new DailyRecommendationsFragment()
        };
        if (hotTagsFirst) {
            String title = TITLES[0];
            TITLES[0] = TITLES[1];
            TITLES[1] = title;
            Fragment fragment = mFragments[0];
            mFragments[0] = mFragments[1];
            mFragments[1] = fragment;
        }
        // BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT：热门标签 tab 靠 onResume 懒加载，
        // 只有真正可见才发请求（对齐 legacy FragmentHotTag 的 userVisibleHint 语义）
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager(),
                FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
            @NonNull
            @Override
            public Fragment getItem(int i) {
                return mFragments[i];
            }

            // 身份跟随内容而非位置，避免改顺序后恢复出标题与内容不一致的页签。
            @Override
            public long getItemId(int position) {
                return hotTagsFirst && position < 2 ? 1 - position : position;
            }

            @NonNull
            @Override
            public Object instantiateItem(@NonNull ViewGroup container, int position) {
                Fragment fragment = (Fragment) super.instantiateItem(container, position);
                // 恢复时复用的 Fragment 也要交给重选回顶和首页刷新。
                mFragments[position] = fragment;
                return fragment;
            }

            @Override
            public int getCount() {
                return TITLES.length;
            }

            @NonNull
            @Override
            public CharSequence getPageTitle(int position) {
                return TITLES[position];
            }
        });
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        MyOnTabSelectedListener listener = new MyOnTabSelectedListener(mFragments);
        baseBind.tabLayout.addOnTabSelectedListener(listener);
    }

    public void forceRefresh() {
        try {
            Fragment fragment = mFragments[baseBind.viewPager.getCurrentItem()];
            if (fragment instanceof FeedFragment) {
                ((FeedFragment) fragment).forceRefresh();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
