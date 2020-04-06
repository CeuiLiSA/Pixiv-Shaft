package ceui.lisa.adapters;

import android.view.View;

import androidx.annotation.NonNull;

import com.facebook.rebound.SimpleSpringListener;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringSystem;

import ceui.lisa.databinding.RecyViewHistoryBinding;

class SpringHolder extends ViewHolder<RecyViewHistoryBinding> {

    Spring spring;

    SpringHolder(@NonNull View itemView) {
        super(itemView);

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
