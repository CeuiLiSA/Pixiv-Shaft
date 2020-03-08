package ceui.lisa.activities;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager.widget.ViewPager;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentTestBinding;
import ceui.lisa.fragments.FragmentCardIllust;
import ceui.lisa.fragments.FragmentSingleIllust;
import ceui.lisa.models.IllustsBean;
import ceui.lisa.transformer.GalleryTransformer;
import ceui.lisa.utils.DataChannel;
import ceui.lisa.utils.GlideUtil;
import ceui.lisa.viewmodel.Dust;
import jp.wasabeef.glide.transformations.BlurTransformation;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;
import static com.bumptech.glide.request.RequestOptions.bitmapTransform;

public class VActivity extends BaseActivity<FragmentTestBinding> {

    private FragmentCardIllust[] mFragments = null;
    private Dust mDust;
    private List<IllustsBean> wtf = new ArrayList<>();

    @Override
    protected int initLayout() {
        return mLayoutID = R.layout.fragment_test;
    }

    @Override
    protected void initView() {

    }

    @Override
    public boolean hideStatusBar() {
        return true;
    }

    @Override
    protected void initData() {
        int index = getIntent().getIntExtra("position", -1);
        wtf.addAll(DataChannel.get().getIllustList());
        mDust = new ViewModelProvider(this).get(Dust.class);
        mFragments = new FragmentCardIllust[wtf.size()];
        mDust.getDust().observe(this, new Observer<List<IllustsBean>>() {
            @Override
            public void onChanged(List<IllustsBean> illustsBeans) {
                baseBind.viewPager.setAdapter(new FragmentPagerAdapter(getSupportFragmentManager(), 0) {
                    @NonNull
                    @Override
                    public Fragment getItem(int position) {
                        if (mFragments[position] == null) {
                            mFragments[position] = FragmentCardIllust.newInstance(position);
                        }
                        return mFragments[position];
                    }

                    @Override
                    public int getCount() {
                        return mFragments.length;
                    }
                });
                if (index >= 0) {
                    baseBind.viewPager.setCurrentItem(index);
                    Glide.with(mContext)
                            .load(GlideUtil.getSquare(wtf.get(index)))
                            .placeholder(baseBind.imageBg.getDrawable())
                            .apply(bitmapTransform(new BlurTransformation(25, 3)))
                            .transition(withCrossFade())
                            .into(baseBind.imageBg);
                }
            }
        });
        baseBind.viewPager.setPageTransformer(true, new GalleryTransformer());
        baseBind.viewPager.setOffscreenPageLimit(3);
        mDust.getDust().setValue(wtf);
    }
}
