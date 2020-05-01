package ceui.lisa.task;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.widget.NestedScrollView;

import ceui.lisa.utils.Common;

public class ScrollBehavior extends CoordinatorLayout.Behavior<NestedScrollView> {

    public ScrollBehavior() {
    }

    public ScrollBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull NestedScrollView child, @NonNull View dependency) {
        return dependency instanceof ImageView;
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent,
                                          @NonNull NestedScrollView child,
                                          @NonNull View dependency) {
        Common.showLog("ScrollBehavior NestedScrollView" + child.getY());
        Common.showLog("ScrollBehavior View" + dependency.getY());
        return true;
    }
}
