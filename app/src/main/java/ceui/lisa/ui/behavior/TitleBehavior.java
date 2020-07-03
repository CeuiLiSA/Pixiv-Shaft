package ceui.lisa.ui.behavior;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.widget.NestedScrollView;

import ceui.lisa.R;
import ceui.lisa.utils.Common;

public class TitleBehavior extends CoordinatorLayout.Behavior {

    private float deltaY;


    public TitleBehavior() {
        super();
    }

    public TitleBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
        Common.showLog("TitleBehavior TitleBehavior attrs");
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        return dependency.getId() == R.id.content_scroll_view;
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        Common.showLog("TitleBehavior onDependentViewChanged ");
        if (deltaY == 0) {
            deltaY = dependency.getY() - child.getHeight();
        }

        float dy = dependency.getY() - child.getHeight();
        if (dy < 0) {
            dy = 0;
        }
        float y = -(dy / deltaY) * child.getHeight();
        child.setTranslationY(y);

        Common.showLog("TitleBehavior dependency.getY() " + dependency.getY());
        Common.showLog("TitleBehavior child.getHeight() " + child.getHeight());

        return true;
    }

    @Override
    public void onNestedScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child,
                               @NonNull View target, int dxConsumed, int dyConsumed,
                               int dxUnconsumed, int dyUnconsumed, int type, @NonNull int[] consumed) {
        Common.showLog("TitleBehavior onDependentViewChanged " + target.getY());
        super.onNestedScroll(coordinatorLayout, child, target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, consumed);
    }


}
