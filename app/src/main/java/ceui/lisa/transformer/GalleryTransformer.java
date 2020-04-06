package ceui.lisa.transformer;

import android.view.View;

import androidx.viewpager.widget.ViewPager;

public class GalleryTransformer implements ViewPager.PageTransformer {


    @Override
    public void transformPage(View page, float position) {


        float scale= 1 - (Math.abs(position) * 0.25f);
        page.setScaleX(scale);
        page.setScaleY(scale);

        if (Math.abs(position) > 0.1) {
            page.setTranslationZ(-Math.abs(position));
        }else {
            page.setTranslationZ(position);
        }
        page.setTranslationX(-50 * position);
    }
}
