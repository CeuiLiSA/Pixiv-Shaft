package ceui.lisa.ui.behavior;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;

import ceui.lisa.R;
import ceui.lisa.utils.Common;

public class FragmentRightHeaderBehavior extends CoordinatorLayout.Behavior<View> {

    public FragmentRightHeaderBehavior() {
    }

    public FragmentRightHeaderBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent, @NonNull View child, int layoutDirection) {
        return super.onLayoutChild(parent, child, layoutDirection);
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        return dependency.getId() == R.id.content_item;
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        child.setTranslationY(dependency.getTranslationY() * 0.6f);
        return true;
    }
//
//    @Override
//    public void onNestedPreScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child,
//                                  @NonNull View target, int dx, int dy, @NonNull int[] consumed, int type) {
//        super.onNestedPreScroll(coordinatorLayout, child, target, dx, dy, consumed, type);
//        if (dy > 0) { // 只处理手指上滑
//            float newTransY = child.getTranslationY() - dy;
//            Common.showLog("onNestedPreScroll " + child.getTranslationY() + child.getClass().getSimpleName());
//            consumed[1] = dy; // consumed[0/1] 分别用于声明消耗了x/y方向多少滑动距离
//            child.setTranslationY(newTransY);
//        }
//    }
//
//    @Override
//    public void onNestedScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child, @NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type, @NonNull int[] consumed) {
//        super.onNestedScroll(coordinatorLayout, child, target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed);
//        if (dyUnconsumed < 0) { // 只处理手指向下滑动的情况
//            float newTransY = child.getTranslationY() - dyUnconsumed;
//            if (newTransY <= 0) {
//                child.setTranslationY(newTransY);
//            } else {
//                child.setTranslationY(0.0f);
//            }
//        }
//    }

    @Override
    public boolean onStartNestedScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child, @NonNull View directTargetChild, @NonNull View target, int axes, int type) {
        boolean result = (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
        Common.showLog("onStartNestedScroll " + result);
        return result;
    }
}
