package ceui.lisa.fragments;

import android.content.Intent;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import com.blankj.utilcode.util.BarUtils;

import ceui.lisa.R;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.databinding.FragmentNewNovelBinding;
import ceui.lisa.utils.Params;

public class FragmentNewNovel extends BaseFragment<FragmentNewNovelBinding> {

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_new_novel;
    }

    @Override
    public void initView() {
        BarUtils.setStatusBarColor(mActivity, android.R.attr.colorPrimary);
        String[] TITLES = new String[]{
                Shaft.getContext().getString(R.string.recommend_illust),
                Shaft.getContext().getString(R.string.hot_tag)
        };
        baseBind.toolbar.setNavigationOnClickListener(v -> {
            mActivity.finish();
        });
        baseBind.toolbar.inflateMenu(R.menu.fragment_left);
        baseBind.toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                if (item.getItemId() == R.id.action_search) {
                    Intent intent = new Intent(mContext, TemplateActivity.class);
                    intent.putExtra(TemplateActivity.EXTRA_FRAGMENT, "搜索");
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager(), 0) {
            @NonNull
            @Override
            public Fragment getItem(int i) {
                if (i == 0) {
                    return new FragmentRecmdNovel();
                } else {
                    return FragmentHotTag.newInstance(Params.TYPE_NOVEL);
                }
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
    }
}
