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
        // content sheet 的 translationY 从 0（完全展开）滑到 -headerHeight（完全折叠），
        // 头部跟着从不透明淡出到全透明。这里无条件写 alpha，保证回到展开态一定复原成 1。
        final float headerHeight = child.getHeight();
        final float progress = headerHeight > 0f
                ? Math.min(1f, Math.max(0f, -dependency.getTranslationY() / headerHeight))
                : 0f;
        child.setAlpha(1f - progress);
        return true;
    }

    @Override
    public boolean onStartNestedScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child, @NonNull View directTargetChild, @NonNull View target, int axes, int type) {
        boolean result = (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
        Common.showLog("onStartNestedScroll " + result);
        return result;
    }
}
