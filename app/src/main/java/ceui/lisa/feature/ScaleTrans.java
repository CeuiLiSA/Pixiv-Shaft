package ceui.lisa.feature;

import android.util.Log;
import android.view.View;

import androidx.viewpager.widget.ViewPager;

import ceui.lisa.utils.Common;

public class ScaleTrans implements ViewPager.PageTransformer {
    @Override
    public void transformPage(View page, float position) {
        Common.showLog("ScaleTrans " + position);
        if (Math.abs(position) <= 1) { // [-1,1]
            float scale = 0.8f + position * position * (-0.2f);
            float trans = 300.0f * position * position;
            Common.showLog("ScaleTrans " + scale);
            if (position < 0) {
                Log.d("google_lenve_fb", "transformPage: scaleX:" + scale);
                page.setScaleX(scale);
                page.setScaleY(scale);
                page.setTranslationX(trans);
            } else {
                page.setScaleX(scale);
                page.setScaleY(scale);
                page.setTranslationX(-trans);
            }
        }
    }
}
