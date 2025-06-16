package ceui.pixiv.ui.home;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewPropertyAnimator;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;

public class ShowTopViewOnScrollBehavior<V extends View> extends CoordinatorLayout.Behavior<V> {

    private static final int STATE_SCROLLED_UP = 1;
    private static final int STATE_SCROLLED_DOWN = 2;

    @IntDef({STATE_SCROLLED_UP, STATE_SCROLLED_DOWN})
    @interface ScrollState {}

    private int height = 0;
    private int currentState = STATE_SCROLLED_UP;
    private ViewPropertyAnimator currentAnimator;

    private static final int ANIM_DURATION = 200;

    public ShowTopViewOnScrollBehavior() {}

    public ShowTopViewOnScrollBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onLayoutChild(CoordinatorLayout parent, V child, int layoutDirection) {
        height = child.getMeasuredHeight();
        return super.onLayoutChild(parent, child, layoutDirection);
    }

    @Override
    public boolean onStartNestedScroll(
            @NonNull CoordinatorLayout coordinatorLayout,
            @NonNull V child,
            @NonNull View directTargetChild,
            @NonNull View target,
            int axes,
            int type) {
        return axes == ViewCompat.SCROLL_AXIS_VERTICAL;
    }

    @Override
    public void onNestedScroll(
            @NonNull CoordinatorLayout coordinatorLayout,
            @NonNull V child,
            @NonNull View target,
            int dxConsumed,
            int dyConsumed,
            int dxUnconsumed,
            int dyUnconsumed,
            int type,
            @NonNull int[] consumed) {

        if (dyConsumed > 0 && currentState != STATE_SCROLLED_UP) {
            // 向上滑动，显示顶部
            slideDown(child);
        } else if (dyConsumed < 0 && currentState != STATE_SCROLLED_DOWN) {
            // 向下滑动，隐藏顶部
            slideUp(child);
        }
    }

    private void slideUp(View child) {
        if (currentAnimator != null) currentAnimator.cancel();
        currentAnimator = child.animate()
                .translationY(-height)
                .setDuration(ANIM_DURATION)
                .withEndAction(() -> currentAnimator = null);
        currentState = STATE_SCROLLED_DOWN;
    }

    private void slideDown(View child) {
        if (currentAnimator != null) currentAnimator.cancel();
        currentAnimator = child.animate()
                .translationY(0)
                .setDuration(ANIM_DURATION)
                .withEndAction(() -> currentAnimator = null);
        currentState = STATE_SCROLLED_UP;
    }
}
