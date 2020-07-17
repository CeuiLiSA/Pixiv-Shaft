package ceui.lisa.ui.behavior;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;

import ceui.lisa.R;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;

public class NestedContentScrollBehavior extends CoordinatorLayout.Behavior<View> {

    private float headerHeight;
    private float toolbarHeight = DensityUtil.dp2px(96.0f);

    public NestedContentScrollBehavior() {
    }

    public NestedContentScrollBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent, @NonNull View child, int layoutDirection) {

        // 首先让父布局按照标准方式解析
        parent.onLayoutChild(child, layoutDirection);
        // 获取到 HeaderView 的高度
        headerHeight = parent.findViewById(R.id.imagesTitleBlockLayout).getMeasuredHeight();




        // 设置 top 从而排在 HeaderView的下面
        ViewCompat.offsetTopAndBottom(child, (int)headerHeight);
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

        if (dy > 0) { // 只处理手指上滑
            float newTransY = child.getTranslationY() - dy;
            Common.showLog("onNestedPreScroll " + child.getTranslationY() + child.getClass().getSimpleName());
            if (newTransY >= (-headerHeight + toolbarHeight)) {
                // 完全消耗滑动距离后没有完全贴顶或刚好贴顶
                // 那么就声明消耗所有滑动距离，并上移 RecyclerView
                consumed[1] = dy; // consumed[0/1] 分别用于声明消耗了x/y方向多少滑动距离
                child.setTranslationY(newTransY);
            } else {
                consumed[1] = (int)(headerHeight + child.getTranslationY() - toolbarHeight);
                child.setTranslationY(-headerHeight + toolbarHeight);
            }
        }
    }

    @Override
    public void onNestedScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child, @NonNull View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int type, @NonNull int[] consumed) {
        super.onNestedScroll(coordinatorLayout, child, target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed);
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
