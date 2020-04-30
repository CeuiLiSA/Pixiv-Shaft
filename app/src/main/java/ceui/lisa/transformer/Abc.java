package ceui.lisa.transformer;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.viewpager.widget.ViewPager;

import ceui.lisa.utils.Common;

public class Abc implements ViewPager.PageTransformer {

    @Override
    public void transformPage(@NonNull View page, float position) {
        if (position < 0) {
            page.setTranslationX(-position * page.getWidth());
        } else {

        }
    }
}
