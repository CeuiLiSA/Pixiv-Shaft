package ceui.lisa.fragments;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentAnimeBinding;

public class FragmentAnime extends BaseFragment<FragmentAnimeBinding> {

    public static FragmentAnime newInstance() {
        Bundle args = new Bundle();
        FragmentAnime fragment = new FragmentAnime();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void initLayout() {
        mLayoutID = R.layout.fragment_anime;
    }

    private static final String[] COLORS = new String[]{
            "#d50000",
            "#c51162",
            "#aa00ff",
            "#6200ea",
            "#304ffe",
            "#2962ff",
            "#0091ea",
            "#00b8d4",
            "#00bfa5",
    };

    @Override
    public void initView(View view) {
        baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager()) {
            @NonNull
            @Override
            public Fragment getItem(int position) {
                return FragmentColor.newInstance(COLORS[position]);
            }

            @Override
            public int getCount() {
                return COLORS.length;
            }
        });
    }
}
