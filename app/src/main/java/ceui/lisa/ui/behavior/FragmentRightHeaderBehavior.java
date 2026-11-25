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

    private int topOverlayHeightPx = 0;

    public FragmentRightHeaderBehavior() {
    }

    public FragmentRightHeaderBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setTopOverlayHeightPx(int heightPx) {
        topOverlayHeightPx = Math.max(0, heightPx);
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
        // 推荐用户区净高 = child 总高减去顶部悬浮头占位；content sheet 上滑经过推荐区时
        // 做 0.6x 视差上移并淡出到透明，随后 sheet 继续穿透顶部导航栏侵入状态栏。
        final float recmdHeight = Math.max(1f, child.getHeight() - topOverlayHeightPx);
        final float clampedTransY = Math.max(-recmdHeight, Math.min(0f, dependency.getTranslationY()));
        child.setTranslationY(clampedTransY * 0.6f);
        final float progress = Math.min(1f, Math.max(0f, -clampedTransY / recmdHeight));
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
