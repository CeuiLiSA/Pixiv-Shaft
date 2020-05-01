package ceui.lisa.task;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import ceui.lisa.utils.Common;


public class SampleHeaderBehavior extends CoordinatorLayout.Behavior<ImageView> {

    // 界面整体向上滑动，达到列表可滑动的临界点
    private boolean upReach;
    // 列表向上滑动后，再向下滑动，达到界面整体可滑动的临界点
    private boolean downReach;
    // 列表上一个全部可见的item位置
    private int lastPosition = -1;

    private int state = 0;

    public SampleHeaderBehavior() {
    }

    public SampleHeaderBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onInterceptTouchEvent(CoordinatorLayout parent, ImageView child, MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downReach = false;
                upReach = false;
                break;
        }
        return super.onInterceptTouchEvent(parent, child, ev);
    }

    @Override
    public boolean onStartNestedScroll(@NonNull CoordinatorLayout coordinatorLayout,
                                       @NonNull ImageView child,
                                       @NonNull View directTargetChild,
                                       @NonNull View target, int axes, int type) {
        return (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override
    public void onNestedPreScroll(@NonNull CoordinatorLayout coordinatorLayout,
                                  @NonNull ImageView child,
                                  @NonNull View target,
                                  int dx, int dy,
                                  @NonNull int[] consumed,
                                  int type) {
        super.onNestedPreScroll(coordinatorLayout, child, target, dx, dy, consumed, type);
        if (target instanceof RecyclerView) {
            RecyclerView list = (RecyclerView) target;
            // 列表第一个全部可见Item的位置
            int pos = ((LinearLayoutManager) list.getLayoutManager()).findFirstCompletelyVisibleItemPosition();
            if (pos == 0 && pos < lastPosition) {
                downReach = true;
            }
            // 整体可以滑动，否则RecyclerView消费滑动事件
            if (canScroll(child, dy) && pos == 0) {
                float finalY = child.getTranslationY() - dy;
                if (finalY < -child.getHeight()) {
                    finalY = -child.getHeight();
                    upReach = true;
                } else if (finalY > 0) {
                    finalY = 0;
                }
                child.setTranslationY(finalY);
                // 让CoordinatorLayout消费滑动事件
                consumed[1] = dy;
            }
            lastPosition = pos;


            Common.showLog(state);
        }
    }

    private boolean canScroll(View child, float scrollY) {
        if (scrollY > 0 && child.getTranslationY() == -child.getHeight() && !upReach) {
            return false;
        }

        if (downReach) {
            return false;
        }
        return true;
    }
}
