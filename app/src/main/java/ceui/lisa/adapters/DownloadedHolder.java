package ceui.lisa.adapters;

import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;

import ceui.lisa.databinding.RecyDownloadedBinding;

class DownloadedHolder extends ViewHolder<RecyDownloadedBinding> {

    Spring spring;

    DownloadedHolder(RecyDownloadedBinding bindView) {
        super(bindView);

        SpringSystem springSystem = SpringSystem.create();
        spring = springSystem.createSpring();
        spring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(40, 5));
        spring.addListener(new SimpleSpringListener() {
            @Override
            public void onSpringUpdate(Spring spring) {
                itemView.setTranslationX((float) spring.getCurrentValue());
            }
        });
    }
}
