package ceui.lisa.fragments;

import android.content.Intent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.appbar.AppBarLayout;

import ceui.lisa.R;
import ceui.lisa.activities.MainActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.SystemBarMetrics;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentLeftBinding;
import ceui.lisa.utils.MyOnTabSelectedListener;
import ceui.lisa.utils.Dev;
import ceui.lisa.utils.Params;
import ceui.lisa.view.OnCheckChangeListener;
import ceui.pixiv.feeds.FeedFragment;
import ceui.pixiv.ui.home.RecmdIllustFeedFragment;
import ceui.pixiv.ui.navigation.HomeShellHost;
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
                    openSearch();
                    return true;
                }
                return false;
            }
        });
        baseBind.wideSearch.setOnClickListener(v -> openSearch());
        HomeShellHost.observe(this, this::applyRailMode);
        applyTopBarScrollSetting();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 「顶栏跟随滚动收起」可能刚在设置里切过：scrollFlags 是布局参数，改完下一帧就生效，不用重启
        applyTopBarScrollSetting();
    }

    private void openSearch() {
        Intent intent = new Intent(mContext, TemplateActivity.class);
        intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SEARCH.key);
        startActivity(intent);
    }

    /**
     * 宽窗口（首页显示侧边导航栏）用 V3 页头，手机排版保留紫色 Toolbar + TabLayout（#1087）。
     * AppBarLayout 本身不隐藏、只收起里面两行：ViewPager 的 appbar_scrolling_view_behavior 以它为
     * 依赖定位，GONE 掉的依赖会留下过期的 bottom，内容就被推到错误的位置。
     */
    private void applyRailMode(boolean railShown) {
        baseBind.toolbar.setVisibility(railShown ? View.GONE : View.VISIBLE);
        baseBind.tabLayout.setVisibility(railShown ? View.GONE : View.VISIBLE);
        baseBind.wideHeader.setVisibility(railShown ? View.VISIBLE : View.GONE);
        baseBind.head.setBackgroundColor(railShown
                ? ContextCompat.getColor(mContext, R.color.v3_bg)
                : Common.resolveThemeAttribute(mContext, androidx.appcompat.R.attr.colorPrimary));
    }

    /**
     * 顶栏（紫色 Toolbar）是否跟随列表滚动收起。
     *
     * scrollFlags 只是 Toolbar 的 AppBarLayout.LayoutParams 上的一个字段，运行时改完重新测量即生效，
     * 不用重启；AppBarLayout 的滚动范围在 onMeasure 里重算，所以改完必须 setLayoutParams 触发一次。
     * 关掉时还要把已经收起的偏移复位——没有任何子项带 scroll 标志之后，没人再把 Toolbar 拉回来。
     * 只影响「推荐」tab：发现 / 动态 的 header 本来就是固定不滚。
     */
    private void applyTopBarScrollSetting() {
        final boolean hide = Shaft.sSettings.isTopBarHideOnScroll();
        ViewGroup.LayoutParams params = baseBind.toolbar.getLayoutParams();
        if (!(params instanceof AppBarLayout.LayoutParams)) {
            return;
        }
        AppBarLayout.LayoutParams lp = (AppBarLayout.LayoutParams) params;
        final int flags = hide
                ? AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
                        | AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
                : 0;
        // 幂等：值没变就不白折腾一次布局（与 SearchActivity#setSearchBarCollapsible 同一套写法）
        if (lp.getScrollFlags() == flags) {
            return;
        }
        // 先展开再清 flags：已经收起的偏移必须在还有 scroll 范围时复位，清完就没人认这个偏移了
        if (!hide) {
            baseBind.appBar.setExpanded(true, false);
        }
        lp.setScrollFlags(flags);
        baseBind.toolbar.setLayoutParams(lp);
    }

    @Override
    public void lazyData() {
        final boolean hotTagsFirst = Shaft.sSettings.isRecommendHotTagsFirst();
        final int[] TITLE_RES = new int[]{R.string.recommend_illust, R.string.hot_tag};
        mFragments = new Fragment[]{
                RecmdIllustFeedFragment.newInstance(RecmdIllustFeedFragment.TYPE_ILLUST),
                HotTagsFeedFragment.newInstance(Params.TYPE_ILLUST)
        };
        if (hotTagsFirst) {
            int title = TITLE_RES[0];
            TITLE_RES[0] = TITLE_RES[1];
            TITLE_RES[1] = title;
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
                return TITLE_RES.length;
            }

            @NonNull
            @Override
            public CharSequence getPageTitle(int position) {
                return getString(TITLE_RES[position]);
            }
        });
        baseBind.tabLayout.setupWithViewPager(baseBind.viewPager);
        MyOnTabSelectedListener listener = new MyOnTabSelectedListener(mFragments);
        baseBind.tabLayout.addOnTabSelectedListener(listener);

        // 宽窗口页头的分段切换：与 TabLayout 共用同一个 ViewPager，点击切页、重复点回顶，
        // 左右滑动时回填选中态
        baseBind.wideTabs.setSegments(TITLE_RES);
        baseBind.wideTabs.setCurrentState(baseBind.viewPager.getCurrentItem());
        baseBind.wideTabs.setListener(new OnCheckChangeListener() {
            @Override
            public void onSelect(int index, View view) {
                baseBind.viewPager.setCurrentItem(index);
            }

            @Override
            public void onReselect(int index, View view) {
                if (mFragments[index] instanceof FeedFragment) {
                    ((FeedFragment) mFragments[index]).scrollToTop();
                }
            }
        });
        baseBind.viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                baseBind.wideTabs.setCurrentState(position);
            }
        });
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
