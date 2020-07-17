package ceui.lisa.ui.behavior;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import ceui.lisa.R;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;

public class NestedHeaderScrollBehavior extends CoordinatorLayout.Behavior<View> {

    private View title, bottom;
    private float toolbarHeight = 0.0f;

    public NestedHeaderScrollBehavior() {
    }

    public NestedHeaderScrollBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
        toolbarHeight = DensityUtil.dp2px(96.0f);
    }

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent, @NonNull View child, int layoutDirection) {
        boolean superResult = super.onLayoutChild(parent, child, layoutDirection);
//        title = child.findViewById(R.id.title);
//        bottom = child.findViewById(R.id.bottom);
        return superResult;
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        return dependency.getId() == R.id.recy_list;
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        child.setTranslationY(dependency.getTranslationY());

//        float ty = Math.abs(dependency.getTranslationY());
//
//        float half = child.getHeight() * 0.5f;
//        if (ty < half) {
//            float alpha = Math.max(0.0f, 1 - ty / half);
//            title.setAlpha(alpha);
//        }
//
//
//        float third = child.getHeight() * 0.33f;
//        float all = child.getHeight() - third - toolbarHeight;
//        float tempY = ty - third;
//        if (ty < third) {
//            bottom.setAlpha(0.0f);
//        } else if (ty >= third && tempY < all) {
//            float alpha = tempY / all;
//            bottom.setAlpha(Math.min(1.0f, alpha));
//        }

        return true;
    }
}
