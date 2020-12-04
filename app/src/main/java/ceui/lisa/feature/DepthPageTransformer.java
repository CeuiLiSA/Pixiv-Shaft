package ceui.lisa.feature;

import android.util.Log;
import android.view.View;

import androidx.viewpager.widget.ViewPager;

public class DepthPageTransformer implements ViewPager.PageTransformer {
    public static final String TAG = "PageTransformer";
    private static final float MIN_SCALE = 0.75f;
    @Override
    public void transformPage(View view, float position) {
        int pageWidth = view.getWidth();

        if (position < -1) {
            view.setAlpha(0);
            Log.d(TAG," transformPage A  , pageId = "+view.toString()
                    +" , position = "+position);
        } else if (position <= 0) {
            view.setAlpha(1);
            view.setTranslationX(0);
            view.setScaleX(1);
            view.setScaleY(1);
            Log.d(TAG," transformPage B  , pageId = "+view.toString()
                    +" , position = "+position);

        } else if (position <= 1) {
            view.setAlpha(1 - position);
            view.setTranslationX(pageWidth * -position);
            float scaleFactor = MIN_SCALE
                    + (1 - MIN_SCALE) * (1 - Math.abs(position));
            view.setScaleX(scaleFactor);
            view.setScaleY(scaleFactor);
            Log.d(TAG," transformPage C  , pageId = "+view.toString()
                    +" , position = "+position);
        } else {
            view.setAlpha(0);
            Log.d(TAG," transformPage D  , pageId = "+view.toString()
                    +" , position = "+position);
        }

    }
}
