package ceui.lisa.ui.behavior;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;

import ceui.lisa.R;

public class UserContentBehavior extends CoordinatorLayout.Behavior<View> {

    private float headerHeight;
    private View contentView;
    private View centerView, toolbarTitleView;
    private OverScroller scroller;
    private int toolbarHeight;
    private Runnable scrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (scroller != null) {
                if (scroller.computeScrollOffset()) {
                    contentView.setTranslationY((float) scroller.getCurrY());
                    ViewCompat.postOnAnimation(contentView, this);
                }
            }
        }
    };

    private void startAutoScroll(int current, int target, int duration) {
        if (scroller == null) {
            scroller = new OverScroller(contentView.getContext());
        }
        if (scroller.isFinished()) {
            contentView.removeCallbacks(scrollRunnable);
            scroller.startScroll(0, current, 0, target - current, duration);
            ViewCompat.postOnAnimation(contentView, scrollRunnable);
        }
    }

    private void stopAutoScroll() {
        if (scroller != null) {
            if (!scroller.isFinished()) {
                scroller.abortAnimation();
                contentView.removeCallbacks(scrollRunnable);
            }
        }
    }

    @Override
    public void onStopNestedScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child, @NonNull View target, int type) {
        super.onStopNestedScroll(coordinatorLayout, child, target, type);
        if (child.getTranslationY() >= 0f || child.getTranslationY() <= -headerHeight) {
            // RV 已经归位（完全折叠或完全展开）
            return;
        }
        if (child.getTranslationY() <= (-headerHeight) * 0.5f) {
            stopAutoScroll();
            startAutoScroll((int)child.getTranslationY(), (int)-headerHeight, DURATION);
        } else {
            stopAutoScroll();
            startAutoScroll((int)child.getTranslationY(), 0, DURATION);
        }
    }

    public static final int DURATION = 600;

    public UserContentBehavior() {
    }

    public UserContentBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        return child.getId() == R.id.imagesTitleBlockLayout;
    }

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent, @NonNull View child, int layoutDirection) {
        // 首先让父布局按照标准方式解析
        parent.onLayoutChild(child, layoutDirection);
        // 获取到 HeaderView 的高度
        View toolbar = parent.findViewById(R.id.toolbar);
        toolbarHeight = toolbar.getMeasuredHeight();
        headerHeight = parent.findViewById(R.id.imagesTitleBlockLayout).getMeasuredHeight() - toolbarHeight;
        parent.findViewById(R.id.content_item).setPadding(0, 0, 0, toolbarHeight);
        toolbarTitleView = toolbar.findViewById(R.id.toolbar_title);
        centerView = parent.findViewById(R.id.center_header);
        contentView = child;
        // 设置 top 从而排在 HeaderView的下面
        ViewCompat.offsetTopAndBottom(child, (int)headerHeight + toolbarHeight);
        return true; // true 表示我们自己完成了解析 不要再自动解析了
    }

    @Override
    public boolean onStartNestedScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child, @NonNull View directTargetChild, @NonNull View target, int axes, int type) {
        return (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override
    public void onNestedPreScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child,
                                  @NonNull View target, int dx, int dy, @NonNull int[] consumed, int type) {
        super.onNestedPreScroll(coordinatorLayout, child, target, dx, dy, consumed, type);
        stopAutoScroll();
        if (dy > 0) { // 只处理手指上滑
            float newTransY = child.getTranslationY() - dy;
            if (newTransY >= (-headerHeight)) {
                // 完全消耗滑动距离后没有完全贴顶或刚好贴顶
                // 那么就声明消耗所有滑动距离，并上移 RecyclerView
                consumed[1] = dy; // consumed[0/1] 分别用于声明消耗了x/y方向多少滑动距离
                child.setTranslationY(newTransY);
            } else {
                consumed[1] = (int)(headerHeight + child.getTranslationY());
                child.setTranslationY(-headerHeight);
            }
        }
    }

    @Override
    public void onNestedScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child, @NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type, @NonNull int[] consumed) {
        if (Math.abs(child.getTranslationY()) == headerHeight) {
            toolbarTitleView.setAlpha(1.0f);
            centerView.setAlpha(0.0f);
        }

        if (Math.abs(child.getTranslationY()) <= 10) {
            toolbarTitleView.setAlpha(0.0f);
            centerView.setAlpha(1.0f);
        }

        super.onNestedScroll(coordinatorLayout, child, target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed);
        stopAutoScroll();
        if (dyUnconsumed < 0) { // 只处理手指向下滑动的情况
            float newTransY = child.getTranslationY() - dyUnconsumed;
            if (newTransY <= 0) {
                child.setTranslationY(newTransY);
            } else {
                child.setTranslationY(0.0f);
            }
        }
    }
}
