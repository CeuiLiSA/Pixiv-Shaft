package ceui.lisa.fragments;

import android.content.Intent;
import android.view.MenuItem;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.utils.MyOnTabSelectedListener;
import ceui.lisa.utils.Params;
import ceui.pixiv.ui.home.RecmdNovelFeedFragment;
import ceui.pixiv.ui.trending.HotTagsFeedFragment;
import ceui.pixiv.ui.navigation.TemplateRoute;

public class FragmentNewNovel extends BaseFragment<ViewpagerWithTablayoutBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.viewpager_with_tablayout;
    }

    @Override
    public void initView() {
        final boolean hotTagsFirst = Shaft.sSettings.isRecommendHotTagsFirst();
        final String[] TITLES = new String[]{
                Shaft.getContext().getString(R.string.recommend_illust),
                Shaft.getContext().getString(R.string.hot_tag)
        };
        // 两个 tab 都迁到了 feeds 框架：推荐 tab（RecmdNovelFeedFragment，autoLoad 默认即时加载）、
        // 热门标签 tab（HotTagsFeedFragment，autoLoad=false 靠 onResume 懒加载）。故 pager 改
        // BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT，热标 tab 只有真正可见才发请求（对齐 legacy
        // FragmentHotTag 的 userVisibleHint 语义，同 FragmentLeft 插画热标）。
        final Fragment[] mFragments = new Fragment[]{
                new RecmdNovelFeedFragment(),
                HotTagsFeedFragment.newInstance(Params.TYPE_NOVEL)
        };
        if (hotTagsFirst) {
            String title = TITLES[0];
            TITLES[0] = TITLES[1];
            TITLES[1] = title;
            Fragment fragment = mFragments[0];
            mFragments[0] = mFragments[1];
            mFragments[1] = fragment;
        }
        baseBind.toolbarTitle.setText(R.string.type_novel);
        baseBind.toolbar.setNavigationOnClickListener(v -> finish());
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
                return hotTagsFirst ? 1 - position : position;
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
}
