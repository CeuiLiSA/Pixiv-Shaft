package ceui.lisa.ui.behavior;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.scwang.smartrefresh.layout.SmartRefreshLayout;

import ceui.lisa.R;
import ceui.lisa.utils.Common;
import ceui.lisa.utils.DensityUtil;

public class HeaderBehavior extends CoordinatorLayout.Behavior<View> {

    private float visibleHeight = 0.0f;
    private float allHeight = 0.0f;
    private float deltaY = 0.0f;

    public HeaderBehavior() {
    }

    public HeaderBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent, @NonNull View child, int layoutDirection) {
        SmartRefreshLayout refreshLayout = parent.findViewById(R.id.refreshLayout);
        int height = parent.findViewById(R.id.bottom_bar).getMeasuredHeight();
        visibleHeight = (float) height;
        allHeight = (float) parent.findViewById(R.id.core_linear).getMeasuredHeight();

        Common.showLog("onLayoutChild visibleHeight " + visibleHeight + " allHeight " + allHeight);
        refreshLayout.setPadding(
                0,
                0,
                0,
                height - DensityUtil.dp2px(16.0f));
        return super.onLayoutChild(parent, child, layoutDirection);
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        return dependency.getId() == R.id.core_linear;
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {
        Common.showLog("dependency.getTranslationY() " + dependency.getY());
        return true;
    }
}
