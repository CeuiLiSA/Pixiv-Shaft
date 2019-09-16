package ceui.lisa.fragments;

import android.view.View;

import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;

import ceui.lisa.R;
import ceui.lisa.databinding.FragmentIllustBinding;
import ceui.lisa.model.IllustsBean;

public class FragmentIllust extends BaseBindFragment<FragmentIllustBinding> {

    private IllustsBean illust;
    private SpringSystem mSpringSystem = SpringSystem.create();
    private Spring topSpring = mSpringSystem.createSpring();

    public static FragmentIllust newInstance(IllustsBean illustsBean) {
        FragmentIllust fragmentIllust = new FragmentIllust();
        fragmentIllust.illust = illustsBean;
        return fragmentIllust;
    }

    @Override
    void initLayout() {
        mLayoutID = R.layout.fragment_illust;
    }

    @Override
    void initData() {
        topSpring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(100, 8));
        topSpring.addListener(new SimpleSpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                baseBind.bottom.setTranslationY((int) spring.getCurrentValue());
            }
        });
        baseBind.go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                topSpring.setEndValue(-500.0f);
            }
        });
    }
}
