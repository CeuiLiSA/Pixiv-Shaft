package ceui.lisa.fragments;

import android.content.Intent;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.blankj.utilcode.util.UiMessageUtils;
import com.google.android.material.tabs.TabLayout;

import ceui.lisa.R;
import ceui.lisa.activities.CoverActivity;
import ceui.lisa.activities.Shaft;
import ceui.lisa.activities.TemplateActivity;
import ceui.lisa.model.IllustsBean;

public class FragmentLeft extends BaseFragment {

    private static final String[] TITLES = new String[]{
            Shaft.getContext().getString(R.string.recommend_illust),
            Shaft.getContext().getString(R.string.hot_tag)
    };

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_left;
    }

    @Override
    View initView(View v) {
        Toolbar toolbar = v.findViewById(R.id.toolbar);
        ImageView head = v.findViewById(R.id.head);
        ViewGroup.LayoutParams headParams = head.getLayoutParams();
        headParams.height = Shaft.statusHeight;
        head.setLayoutParams(headParams);
        toolbar.setNavigationOnClickListener(view -> {
            if (getActivity() instanceof CoverActivity) {
                ((CoverActivity) getActivity()).getDrawer().openDrawer(Gravity.START);
            }
        });
        toolbar.inflateMenu(R.menu.fragment_left);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
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
        ViewPager viewPager = v.findViewById(R.id.view_pager);
        viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
            @NonNull
            @Override
            public Fragment getItem(int i) {
                if (i == 0) {
                    return FragmentRecmdManga.newInstance("插画");
                } else {
                    return new FragmentHotTag();
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
        TabLayout tabLayout = v.findViewById(R.id.tab_layout);
        tabLayout.setupWithViewPager(viewPager);
        return v;
    }

    @Override
    void initData() {

    }
}
