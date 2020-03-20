package ceui.lisa.fragments;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;

import ceui.lisa.R;
import ceui.lisa.core.BindFragment;
import ceui.lisa.databinding.FragmentTestBinding;
import ceui.lisa.transformer.GalleryTransformer;
import ceui.lisa.utils.DataChannel;

public class FragmentTest extends BindFragment<FragmentTestBinding> {

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
            "#00c853"
    };
    private FragmentSingleIllust[] mFragments = new FragmentSingleIllust[DataChannel.get().getIllustList().size()];

    @Override
    public void getLayout() {
        mLayoutID = R.layout.fragment_test;
    }

    @Override
    public void initData() {
        bind.viewPager.setAdapter(new FragmentPagerAdapter(getChildFragmentManager(), 0) {
            @NonNull
            @Override
            public Fragment getItem(int position) {
                if (mFragments[position] == null) {
                    mFragments[position] = FragmentSingleIllust.newInstance(position);
                }
                return mFragments[position];
            }

            @Override
            public int getCount() {
                return mFragments.length;
            }
        });
        bind.viewPager.setPageTransformer(true, new GalleryTransformer());
        bind.viewPager.setCurrentItem(COLORS.length / 2);
        bind.viewPager.setOffscreenPageLimit(3);
    }
}
