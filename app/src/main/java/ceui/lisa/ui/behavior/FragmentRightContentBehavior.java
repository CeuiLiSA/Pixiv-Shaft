package ceui.lisa.ui.behavior;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import ceui.lisa.R;

public class FragmentRightContentBehavior extends CoordinatorLayout.Behavior<View> {

    public interface OffsetListener {
        void onSheetPositionChanged(float sheetTopPx, float translationY, float headerHeightPx, float recmdHeightPx);
    }

    private float headerHeight;
    private int topOverlayHeightPx = 0;
    private View contentView;
    private OverScroller scroller;
    @Nullable
    private OffsetListener offsetListener;

    private final Runnable scrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (scroller != null) {
                if (scroller.computeScrollOffset()) {
                    applyTranslationY((float) scroller.getCurrY());
                    ViewCompat.postOnAnimation(contentView, this);
                }
            }
        }
    };

    public FragmentRightContentBehavior() {
    }

    public FragmentRightContentBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setTopOverlayHeightPx(int heightPx) {
        topOverlayHeightPx = Math.max(0, heightPx);
        notifyOffset();
    }

    public void setOffsetListener(@Nullable OffsetListener listener) {
        offsetListener = listener;
        notifyOffset();
    }

    /** 回到展开态或安全落位，供点击标题栏回顶时确保首条内容露在筛选条下方。 */
    public void ensureDockedOrExpanded() {
        if (contentView == null) {
            return;
        }
        float recmdHeight = getRecmdHeight();
        if (contentView.getTranslationY() < -recmdHeight) {
            stopAutoScroll();
            startAutoScroll((int) contentView.getTranslationY(), (int) -recmdHeight, 240);
        }
    }

    private float getRecmdHeight() {
        return Math.max(0f, headerHeight - topOverlayHeightPx);
    }

    private void applyTranslationY(float targetY) {
        if (contentView == null) {
            return;
        }
        float clamped = Math.max(-headerHeight, Math.min(0f, targetY));
        contentView.setTranslationY(clamped);
        notifyOffset();
    }

    private void notifyOffset() {
        if (offsetListener == null || contentView == null) {
            return;
        }
        float ty = contentView.getTranslationY();
        float sheetTop = headerHeight + ty;
        offsetListener.onSheetPositionChanged(sheetTop, ty, headerHeight, getRecmdHeight());
    }

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
        float recmdHeight = getRecmdHeight();
        float ty = child.getTranslationY();
        // 仅在「推荐用户」半折叠区间 (-recmdHeight, 0) 内吸附展开或收起；
        // 当 sheet 已进入顶部侵入过渡带 [-headerHeight, -recmdHeight] 时不强行弹回，保持跟手连续。
        if (ty >= 0f || ty <= -recmdHeight) {
            return;
        }
        if (ty <= -recmdHeight * 0.5f) {
            stopAutoScroll();
            startAutoScroll((int) ty, (int) -recmdHeight, 600);
        } else {
            stopAutoScroll();
            startAutoScroll((int) ty, 0, 500);
        }
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        return child.getId() == R.id.imagesTitleBlockLayout;
    }

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent, @NonNull View child, int layoutDirection) {
        parent.onLayoutChild(child, layoutDirection);
        View header = parent.findViewById(R.id.imagesTitleBlockLayout);
        headerHeight = header != null ? header.getMeasuredHeight() : 0f;
        contentView = child;
        ViewCompat.offsetTopAndBottom(child, (int) headerHeight);
        if (child.getTranslationY() < -headerHeight) {
            child.setTranslationY(-headerHeight);
        }
        notifyOffset();
        return true;
    }

    @Override
    public boolean onStartNestedScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child, @NonNull View directTargetChild, @NonNull View target, int axes, int type) {
        return (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
    }

    private static boolean canTargetScrollUp(@NonNull View target) {
        // SwipeRefreshLayout 是 NestedScrollingChild3，CoordinatorLayout 收到的 target 就是它而非内部的
        // RecyclerView；SwipeRefreshLayout 未覆写 View.canScrollVertically(-1)（永远返回 false），
        // 必须走 canChildScrollUp() 查询内层列表是否还能继续向下滚回顶部，否则列表深处刚一下滑就会把
        // sheet 拽离屏幕顶露出顶部黑色空洞。
        if (target instanceof SwipeRefreshLayout) {
            return ((SwipeRefreshLayout) target).canChildScrollUp();
        }
        return target.canScrollVertically(-1);
    }

    @Override
    public void onNestedPreScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child,
                                  @NonNull View target, int dx, int dy, @NonNull int[] consumed, int type) {
        super.onNestedPreScroll(coordinatorLayout, child, target, dx, dy, consumed, type);
        stopAutoScroll();
        if (dy > 0) { // 手指上滑：先收起推荐用户，再将内容 sheet 平滑送入顶部导航栏与状态栏下方
            float ty = child.getTranslationY();
            if (ty > -headerHeight) {
                float newTransY = ty - dy;
                if (newTransY >= -headerHeight) {
                    consumed[1] = dy;
                    applyTranslationY(newTransY);
                } else {
                    consumed[1] = (int) (headerHeight + ty);
                    applyTranslationY(-headerHeight);
                }
            }
        } else if (dy < 0 && !canTargetScrollUp(target)) {
            // 列表确实已回顶且 sheet 仍侵入在顶部导航栏后方时，先将 sheet 退回筛选条对齐位，
            // 确保首条卡片完整露出且下拉刷新转圈从筛选条下方出现。
            float recmdHeight = getRecmdHeight();
            float ty = child.getTranslationY();
            if (ty < -recmdHeight) {
                float newTransY = ty - dy;
                if (newTransY <= -recmdHeight) {
                    consumed[1] = dy;
                    applyTranslationY(newTransY);
                } else {
                    consumed[1] = (int) (ty + recmdHeight);
                    applyTranslationY(-recmdHeight);
                }
            }
        }
    }

    @Override
    public void onNestedScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child, @NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type, @NonNull int[] consumed) {
        super.onNestedScroll(coordinatorLayout, child, target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed);
        stopAutoScroll();
        if (dyUnconsumed < 0) { // 手指向下继续拉出内容 sheet 与推荐用户区
            float ty = child.getTranslationY();
            if (ty < 0f) {
                float newTransY = Math.min(0f, ty - dyUnconsumed);
                int taken = Math.max(dyUnconsumed, Math.min(0, Math.round(ty - newTransY)));
                applyTranslationY(newTransY);
                consumed[1] += taken;
            }
        }
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        return super.onDependentViewChanged(parent, child, dependency);
    }
}
