package ceui.lisa.fragments;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.databinding.ViewpagerWithTablayoutBinding;
import ceui.lisa.utils.MyOnTabSelectedListener;
import ceui.pixiv.ui.newworks.LatestIllustFeedFragment;
import ceui.pixiv.ui.newworks.LatestNovelFeedFragment;

public class FragmentNew extends BaseFragment<ViewpagerWithTablayoutBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.viewpager_with_tablayout;
    }

    @NonNull
    @Override
    protected ViewpagerWithTablayoutBinding bindView(@NonNull View root) {
        return ViewpagerWithTablayoutBinding.bind(root);
    }

    @Override
    public void initView() {
        String[] CHINESE_TITLES = new String[]{
                Shaft.getContext().getString(R.string.type_illust),
                Shaft.getContext().getString(R.string.type_manga),
                Shaft.getContext().getString(R.string.type_novel)
        };
        // 三个 tab 均已迁 feeds 框架（LatestIllustFeedFragment / LatestNovelFeedFragment，
        // 懒加载 autoLoad=false）。子 pager 必须用 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT，
        // 否则相邻 tab 被预创建即 RESUMED，懒加载失效、全部偷偷发请求。
        final Fragment[] mFragments = new Fragment[]{
                LatestIllustFeedFragment.newInstance(LatestIllustFeedFragment.TYPE_ILLUST),
                LatestIllustFeedFragment.newInstance(LatestIllustFeedFragment.TYPE_MANGA),
                new LatestNovelFeedFragment()
        };
        baseBind.toolbar.setNavigationOnClickListener(v -> mActivity.finish());
        baseBind.toolbarTitle.setText(R.string.string_204);
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager(),
                FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
            @NonNull
            @Override
            public Fragment getItem(int position) {
                return mFragments[position];
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
        MyOnTabSelectedListener listener = new MyOnTabSelectedListener(mFragments);
        baseBind.tabLayout.addOnTabSelectedListener(listener);
    }
}
