package ceui.lisa.ui.behavior;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;

import ceui.lisa.R;
import ceui.lisa.utils.Common;

public class UserHeaderBehavior extends CoordinatorLayout.Behavior<View> {

    private float headerHeight;
    private int toolbarHeight;
    private View centerView, toolbarTitleView;

    public UserHeaderBehavior() {
    }

    public UserHeaderBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent, @NonNull View child, int layoutDirection) {
        View toolbar = parent.findViewById(R.id.toolbar);
        toolbarHeight = toolbar.getMeasuredHeight();
        headerHeight = parent.findViewById(R.id.imagesTitleBlockLayout).getMeasuredHeight() - toolbarHeight;
        toolbarTitleView = toolbar.findViewById(R.id.toolbar_title);
        centerView = parent.findViewById(R.id.center_header);
        return super.onLayoutChild(parent, child, layoutDirection);
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        return dependency.getId() == R.id.content_item;
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        Common.showLog("onDependentViewChanged " + child.getTranslationY());
        toolbarTitleView.setAlpha(- (child.getTranslationY() /headerHeight));
        centerView.setAlpha(1 - child.getTranslationY() / - headerHeight);

        if (Math.abs(child.getTranslationY()) < 10) {
            toolbarTitleView.setAlpha(0.0f);
            centerView.setAlpha(1.0f);
        }

        child.setTranslationY(dependency.getTranslationY());
        return true;
    }

    @Override
    public boolean onStartNestedScroll(@NonNull CoordinatorLayout coordinatorLayout, @NonNull View child, @NonNull View directTargetChild, @NonNull View target, int axes, int type) {
        boolean result = (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
        Common.showLog("onStartNestedScroll " + result);
        return result;
    }
}
